/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.security.user;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jackrabbit.guava.common.collect.Iterators;
import org.apache.jackrabbit.commons.iterator.AbstractLazyIterator;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.PropertyValues;
import org.apache.jackrabbit.oak.plugins.tree.TreeUtil;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.user.AuthorizableType;
import org.apache.jackrabbit.oak.spi.security.user.util.UserUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code MembershipProvider} implementation storing group membership information
 * with the {@code Tree} associated with a given {@link org.apache.jackrabbit.api.security.user.Group}.
 *
 * As of Oak the {@code MembershipProvider} automatically chooses an appropriate storage structure
 * depending on the number of group members. If the number of members is low they are stored as
 * {@link javax.jcr.PropertyType#WEAKREFERENCE} in the {@link #REP_MEMBERS} multi value property. This is similar to
 * Jackrabbit 2.x.
 *
 * If the number of members is high the {@code MembershipProvider} will create an intermediate node list to reduce the
 * size of the multi value properties below a {@link #REP_MEMBERS_LIST} node. The provider will maintain a number of
 * sub nodes of type {@link #NT_REP_MEMBER_REFERENCES} that again store the member references in a {@link #REP_MEMBERS}
 * property.
 *
 * Note that the writing of the members is done in {@link MembershipWriter} so that the logic can be re-used by the
 * migration code.
 *
 * The current implementation uses a fixed threshold value of {@link MembershipWriter#DEFAULT_MEMBERSHIP_THRESHOLD} before creating
 * {@link #NT_REP_MEMBER_REFERENCES} sub nodes.
 *
 * Example Group with few members (irrelevant properties excluded):
 * <xmp>
     {
         "jcr:primaryType": "rep:Group",
         "rep:principalName": "contributor",
         "rep:members": [
             "429bbd5b-46a6-3c3d-808b-5fd4219d5c4d",
             "ca58c408-fe06-357e-953c-2d23ffe1e096",
             "3ebb1c04-76dd-317e-a9ee-5164182bc390",
             "d3c827d3-4db2-30cc-9c41-0ed8117dbaff",
             "f5777a0b-a933-3b4d-9405-613d8bc39cc7",
             "fdd1547a-b19a-3154-90da-1eae8c2c3504",
             "65c3084e-abfc-3719-8223-72c6cb9a3d6f"
         ]
     }
 * </xmp>
 *
 * Example Group with many members (irrelevant properties excluded):
 * <xmp>
     {
         "jcr:primaryType": "rep:Group",
         "rep:principalName": "employees",
         "rep:membersList": {
             "jcr:primaryType": "rep:MemberReferencesList",
             "0": {
                 "jcr:primaryType": "rep:MemberReferences",
                 "rep:members": [
                     "429bbd5b-46a6-3c3d-808b-5fd4219d5c4d",
                     "ca58c408-fe06-357e-953c-2d23ffe1e096",
                     ...
                 ]
             },
             ...
             "341": {
                 "jcr:primaryType": "rep:MemberReferences",
                 "rep:members": [
                     "fdd1547a-b19a-3154-90da-1eae8c2c3504",
                     "65c3084e-abfc-3719-8223-72c6cb9a3d6f",
                     ...
                 ]
             }
         }
     }
 * </xmp>
 */
class MembershipProvider extends AuthorizableBaseProvider {

    private static final Logger log = LoggerFactory.getLogger(MembershipProvider.class);

    private final MembershipWriter writer = new MembershipWriter();

    /**
     * Creates a new membership provider
     * @param root the current root
     * @param config the security configuration
     */
    MembershipProvider(@NotNull Root root, @NotNull ConfigurationParameters config) {
        super(root, config);
    }

    /**
     * Sets the size of the membership property threshold. This is currently only useful for testing.
     * @param membershipSizeThreshold the size of the membership property threshold
     */
    void setMembershipSizeThreshold(int membershipSizeThreshold) {
        writer.setMembershipSizeThreshold(membershipSizeThreshold);
    }

    /**
     * Returns an iterator over all membership paths of the given authorizable.
     *
     * @param authorizableTree the authorizable tree
     * @param includeInherited {@code true} to include inherited memberships
     * @return an iterator over all membership trees.
     */
    @NotNull
    Iterator<Tree> getMembership(@NotNull Tree authorizableTree, final boolean includeInherited) {
        return getMembership(authorizableTree, includeInherited, new HashSet<>());
    }

    /**
     * Returns an iterator over all membership paths of the given authorizable.
     *
     * @param authorizableTree the authorizable tree
     * @param includeInherited {@code true} to include inherited memberships
     * @param processedPaths helper set that contains the processed paths
     * @return an iterator over all membership paths.
     */
    @NotNull
    private Iterator<Tree> getMembership(@NotNull Tree authorizableTree, final boolean includeInherited,
                                           @NotNull final Set<String> processedPaths) {
        final Iterable<Tree> refTrees = identifierManager.getReferences(
                authorizableTree, REP_MEMBERS, NT_REP_MEMBER_REFERENCES, true
        );
        return new MembershipIterator(refTrees.iterator(), includeInherited, processedPaths);
    }

    /**
     * Tests if the membership of the specified {@code authorizableTree}
     * contains the given target group as defined by {@code groupTree}.
     *
     * @param authorizableTree The tree of the authorizable for which to resolve the membership.
     * @param groupPath The path of the group which needs to be tested.
     * @return {@code true} if the group is contained in the membership of the specified authorizable.
     */
    private boolean hasMembership(@NotNull Tree authorizableTree, @NotNull String groupPath) {
        return Iterators.contains(Iterators.transform(getMembership(authorizableTree, true), Tree::getPath), groupPath);
    }

    /**
     * Returns an iterator over all member paths of the given group.
     *
     * @param groupTree the group tree
     * @param includeInherited {@code true} to include inherited members
     * @return an iterator over all member trees
     */
    @NotNull
    Iterator<Tree> getMembers(@NotNull Tree groupTree, boolean includeInherited) {
        return getMembers(groupTree, getContentID(groupTree), includeInherited, new HashSet<>());
    }

    @NotNull
    Iterator<String> getDeclaredMemberContentIDs(@NotNull Tree groupTree) {
        return getDeclaredMemberReferenceIterator(groupTree);
    }

    /**
     * Returns an iterator over all member paths of the given group.
     *
     * @param groupTree the group tree
     * @param includeInherited {@code true} to include inherited members
     * @param processedRefs helper set that contains the references that are already processed.
     * @return an iterator over all member paths
     */
    @NotNull
    private Iterator<Tree> getMembers(@NotNull final Tree groupTree,
                                        @NotNull final String groupContentId,
                                        final boolean includeInherited,
                                        @NotNull final Set<String> processedRefs) {
        MemberReferenceIterator mrit = new MemberReferenceIterator(groupTree) {
            @Override
            protected boolean hasProcessedReference(@NotNull String value) {
                if (groupContentId.equals(value)) {
                    log.warn("Cyclic group membership detected for contentId {}", groupContentId);
                    return false;
                }
                return processedRefs.add(value);
            }
        };

        return new AbstractMemberIterator<String>(mrit) {

            @Override
            protected Tree internalGetNext(@NotNull String value) {
                Tree next = identifierManager.getTree(PropertyValues.newWeakReference(value));

                // eventually remember groups for including inherited members
                if (includeInherited && UserUtil.isType(next, AuthorizableType.GROUP)) {
                    remember(next);
                }
                return next;
            }

            @NotNull
            @Override
            protected Iterator<Tree> getNextIterator(@NotNull Tree groupTree) {
                return getMembers(groupTree, groupContentId, true, processedRefs);
            }
        };
    }

    /**
     * Returns {@code true} if the given {@code groupTree} contains a member with the given {@code authorizableTree}
     *
     * @param groupTree  The new member to be tested for cyclic membership.
     * @param authorizableTree The authorizable to check
     *
     * @return true if the group has given member.
     */
    boolean isMember(@NotNull Tree groupTree, @NotNull Tree authorizableTree) {
        if (!hasMembers(groupTree)) {
            return false;
        }
        if (pendingChanges(groupTree)) {
            return Iterators.contains(Iterators.transform(getMembers(groupTree, true), Tree::getPath), authorizableTree.getPath());
        } else {
            return hasMembership(authorizableTree, groupTree.getPath());
        }
    }

    boolean isDeclaredMember(@NotNull Tree groupTree, @NotNull Tree authorizableTree) {
        if (!hasMembers(groupTree)) {
            return false;
        }

        String contentId = getContentID(authorizableTree);
        MemberReferenceIterator refs = getDeclaredMemberReferenceIterator(groupTree);
        return Iterators.contains(refs, contentId);
    }

    /**
     * Utility to determine if a given group has any members.
     *
     * @param groupTree The tree of the target group.
     * @return {@code true} if the group has any members i.e. if it has a rep:members
     * property or a rep:membersList child node.
     */
    private static boolean hasMembers(@NotNull Tree groupTree) {
        return groupTree.getPropertyStatus(REP_MEMBERS) != null || groupTree.hasChild(REP_MEMBERS_LIST);
    }

    /**
     * Determine if the group has (potentially) been modified in which case the
     * query can't be used:
     * - rep:members property has been modified
     * - any potential modification in the member-ref-list subtree, which is not
     * easy to detect => relying on pending changes on the root object
     *
     * @param groupTree The tree of the target group.
     * @return {@code true} if the specified group tree has an unmodified rep:members
     * property or if the root has pending changes.
     */
    private boolean pendingChanges(@NotNull Tree groupTree) {
        Tree.Status memberPropStatus = groupTree.getPropertyStatus(REP_MEMBERS);
        // rep:members is new or has been modified or root has pending changes
        return Tree.Status.UNCHANGED != memberPropStatus || root.hasPendingChanges();
    }

    /**
     * Adds a new member to the given {@code groupTree}.
     * @param groupTree the group to add the member to
     * @param newMemberTree the tree of the new member
     * @return {@code true} if the member was added
     */
    boolean addMember(@NotNull Tree groupTree, @NotNull Tree newMemberTree) {
        return writer.addMember(groupTree, getContentID(newMemberTree));
    }

    /**
     * Add the members from the given group.
     *
     * @param groupTree group to add the new members
     * @param memberIds Map of 'contentId':'memberId' of all members to be added.
     * @return the set of member IDs that was not successfully processed.
     */
    Set<String> addMembers(@NotNull Tree groupTree, @NotNull Map<String, String> memberIds) {
        return writer.addMembers(groupTree, memberIds);
    }

    /**
     * Removes the member from the given group.
     *
     * @param groupTree group to remove the member from
     * @param memberTree member to remove
     * @return {@code true} if the member was removed.
     */
    boolean removeMember(@NotNull Tree groupTree, @NotNull Tree memberTree) {
        if (writer.removeMember(groupTree, getContentID(memberTree))) {
            return true;
        } else {
            log.debug("Authorizable {} was not member of {}", memberTree.getName(), groupTree.getName());
            return false;
        }
    }

    /**
     * Removes the members from the given group.
     *
     * @param groupTree group to remove the member from
     * @param memberIds Map of 'contentId':'memberId' of all members that need to be removed.
     * @return the set of member IDs that was not successfully processed.
     */
    Set<String> removeMembers(@NotNull Tree groupTree, @NotNull Map<String, String> memberIds) {
        return writer.removeMembers(groupTree, memberIds);
    }

    private MemberReferenceIterator getDeclaredMemberReferenceIterator(@NotNull Tree groupTree) {
        return new MemberReferenceIterator(groupTree) {
            @Override
            protected boolean hasProcessedReference(@NotNull String value) {
                return true;
            }
        };
    }

    /**
     * Iterator that provides member references based on the rep:members properties of a underlying tree iterator.
     */
    private abstract static class MemberReferenceIterator extends AbstractLazyIterator<String> {

        private final Iterator<Tree> trees;
        private Iterator<String> propertyValues;

        private MemberReferenceIterator(@NotNull Tree groupTree) {
            this.trees = Iterators.concat(
                    Iterators.singletonIterator(groupTree),
                    groupTree.getChild(REP_MEMBERS_LIST).getChildren().iterator()
            );
        }

        @Override
        protected String getNext() {
            String next = null;
            while (next == null) {
                if (propertyValues == null) {
                    // check if there are more trees that can provide a rep:members property
                    if (!trees.hasNext()) {
                        // if not, we're done
                        break;
                    }
                    PropertyState property = trees.next().getProperty(REP_MEMBERS);
                    if (property != null) {
                        propertyValues = property.getValue(Type.STRINGS).iterator();
                    }
                } else if (!propertyValues.hasNext()) {
                    // if there are no more values left, reset the iterator
                    propertyValues = null;
                } else {
                    String value = propertyValues.next();
                    if (hasProcessedReference(value)) {
                        next = value;
                    }
                }
            }
            return next;
        }

        protected abstract boolean hasProcessedReference(@NotNull String value);
    }

    private abstract static class AbstractMemberIterator<T> extends AbstractLazyIterator<Tree> {

        private final Iterator<T> references;
        private List<Tree> groupTrees;
        private Iterator<Tree> parent;

        AbstractMemberIterator(@NotNull Iterator<T> references) {
            this.references = references;
        }

        @Override
        protected Tree getNext() {
            Tree next = null;
            while (next == null) {
                if (references.hasNext()) {
                    next = internalGetNext(references.next());
                } else if (parent != null) {
                    if (parent.hasNext()) {
                        next = parent.next();
                    } else {
                        // force retrieval of next parent iterator
                        parent = null;
                    }
                } else {
                    // try to retrieve the next 'parent' iterator for the first
                    // group tree remembered in the list.
                    if (groupTrees == null || groupTrees.isEmpty()) {
                        // no more parents to process => reset the iterator.
                        break;
                    } else {
                        parent = getNextIterator(groupTrees.remove(0));
                    }
                }
            }
            return next;
        }

        /**
         * Remember a group that needs to be search for references ('parent')
         * once all 'references' have been processed.
         *
         * @param groupTree A tree associated with a group
         * @see #getNextIterator(Tree)
         */
        void remember(@NotNull Tree groupTree) {
            if (groupTrees == null) {
                groupTrees = new ArrayList<>();
            }
            groupTrees.add(groupTree);
        }

        /**
         * Abstract method to obtain the next authorizable path from the given
         * reference value.
         *
         * @param nextReference The next reference as obtained from the iterator.
         * @return The path of the authorizable identified by {@code nextReference}
         * or {@code null} if it cannot be resolved.
         */
        @Nullable
        protected abstract Tree internalGetNext(@NotNull T nextReference);

        /**
         * Abstract method to retrieve the next member iterator for the given
         * {@code groupTree}.
         *
         * @param groupTree Tree referring to a group.
         * @return The next member reference 'parent' iterator to be processed.
         */
        @NotNull
        protected abstract Iterator<Tree> getNextIterator(@NotNull Tree groupTree);
    }

    private final class MembershipIterator extends AbstractMemberIterator<Tree> {

        private final boolean includeInherited;
        private final Set<String> processedPaths;

        private MembershipIterator(@NotNull Iterator<Tree> references, boolean includeInherited, @NotNull Set<String> processedPaths) {
            super(references);
            this.includeInherited = includeInherited;
            this.processedPaths = processedPaths;
        }

        @Override
        protected Tree internalGetNext(@NotNull Tree refTree) {
            Tree next = null;

            Tree groupTree = getGroupTree(refTree);
            if (groupTree != null) {
                if (processedPaths.add(groupTree.getPath())) {
                    // we didn't see this path before, so continue
                    next = groupTree;
                    if (includeInherited) {
                        // inject a parent iterator if inherited memberships is requested
                        remember(groupTree);
                    }
                }
            } else {
                log.debug("Not a membership reference {}", refTree);
            }
            return next;
        }

        @NotNull
        @Override
        protected Iterator<Tree> getNextIterator(@NotNull Tree groupTree) {
            return getMembership(groupTree, true, processedPaths);
        }

        @Nullable
        private Tree getGroupTree(@NotNull Tree tree) {
            Tree groupTree = tree;
            while (!groupTree.isRoot()) {
                String ntName = TreeUtil.getPrimaryTypeName(groupTree);
                if (NT_REP_GROUP.equals(ntName)) {
                    return groupTree;
                } else {
                    groupTree = groupTree.getParent();
                }
            }
            return null;
        }
    }
}
