/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.plugins.index.lucene.util;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import org.apache.jackrabbit.oak.plugins.index.lucene.IndexDefinition;
import org.apache.lucene.store.BaseDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Maps.newConcurrentMap;

public class IndexCopier {
    private static final Set<String> REMOTE_ONLY = ImmutableSet.of("segments.gen");

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Executor executor;
    private final File indexRootDir;

    public IndexCopier(Executor executor, File indexRootDir) {
        this.executor = executor;
        this.indexRootDir = indexRootDir;
    }

    public Directory wrap(String indexPath, IndexDefinition definition, Directory remote) throws IOException {
        Directory local = createLocalDir(indexPath, definition);
        return new CopyOnReadDirectory(remote, local);
    }

    protected Directory createLocalDir(String indexPath, IndexDefinition definition) throws IOException {
        //TODO Handle the reindex case. In case of reindex a new directory should be used
        String subDir = Hashing.sha256().hashString(indexPath, Charsets.UTF_8).toString();
        File indexDir = new File(indexRootDir, subDir);
        if (!indexDir.exists()) {
            checkState(indexDir.mkdirs(), "Cannot create directory %s", indexDir);
        }
        return FSDirectory.open(indexDir);
    }

    /**
     * Directory implementation which lazily copies the index files from a
     * remote directory in background.
     */
    private class CopyOnReadDirectory extends BaseDirectory {
        private final Directory remote;
        private final Directory local;

        private final ConcurrentMap<String, FileReference> files = newConcurrentMap();

        public CopyOnReadDirectory(Directory remote, Directory local) throws IOException {
            this.remote = remote;
            this.local = local;
        }

        @Override
        public String[] listAll() throws IOException {
            return remote.listAll();
        }

        @Override
        public boolean fileExists(String name) throws IOException {
            return remote.fileExists(name);
        }

        @Override
        public void deleteFile(String name) throws IOException {
            throw new UnsupportedOperationException("Cannot delete in a ReadOnly directory");
        }

        @Override
        public long fileLength(String name) throws IOException {
            return remote.fileLength(name);
        }

        @Override
        public IndexOutput createOutput(String name, IOContext context) throws IOException {
            throw new UnsupportedOperationException("Cannot write in a ReadOnly directory");
        }

        @Override
        public void sync(Collection<String> names) throws IOException {
            remote.sync(names);
        }

        @Override
        public IndexInput openInput(String name, IOContext context) throws IOException {
            if (REMOTE_ONLY.contains(name)) {
                return remote.openInput(name, context);
            }

            FileReference ref = files.get(name);
            if (ref != null) {
                if (ref.isLocalValid()) {
                    return files.get(name).openLocalInput(context);
                } else {
                    return remote.openInput(name, context);
                }
            }

            FileReference toPut = new FileReference(name);
            FileReference old = files.putIfAbsent(name, toPut);
            if (old == null) {
                copy(toPut);
            }

            //If immediate executor is used the result would be ready right away
            if (toPut.isLocalValid()) {
                return toPut.openLocalInput(context);
            }

            return remote.openInput(name, context);
        }

        private void copy(final FileReference reference) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    String name = reference.name;
                    try {
                        if (!local.fileExists(name)) {
                            remote.copy(local, name, name, IOContext.READ);
                            reference.markValid();
                        } else {
                            long localLength = local.fileLength(name);
                            long remoteLength = remote.fileLength(name);

                            //Do a simple consistency check. Ideally Lucene index files are never
                            //updated but still do a check if the copy is consistent
                            if (localLength != remoteLength) {
                                log.warn("Found local copy for {} in {} but size of local {} differs from remote {}. " +
                                                "Content would be read from remote file only",
                                        name, local, localLength, remoteLength);
                            } else {
                                reference.markValid();
                            }
                        }
                    } catch (IOException e) {
                        //TODO In case of exception there would not be any other attempt
                        //to download the file. Look into support for retry
                        log.warn("Error occurred while copying file [{}] " +
                                "from {} to {}", name, remote, local, e);
                    }
                }
            });
        }

        /**
         * On close file which are not present in remote are removed from local.
         * CopyOnReadDir is opened at different revisions of the index state
         *
         * CDir1 - V1
         * CDir2 - V2
         *
         * Its possible that two different IndexSearcher are opened at same local
         * directory but pinned to different revisions. So while removing it must
         * be ensured that any currently opened IndexSearcher does not get affected.
         * The way IndexSearchers get created in IndexTracker it ensures that new searcher
         * pinned to newer revision gets opened first and then existing ones are closed.
         *
         *
         * @throws IOException
         */
        @Override
        public void close() throws IOException {
            //TODO Handle cleanup of orphaned index directory caused by reindex
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try{
                        removeDeletedFiles();
                    } catch (IOException e) {
                        log.warn("Error occurred while removing deleted files from Local {}, " +
                                "Remote {}", local, remote, e);
                    }

                    try {
                        local.close();
                        remote.close();
                    } catch (IOException e) {
                        log.warn("Error occurred while closing directory ", e);
                    }
                }
            });
        }

        private void removeDeletedFiles() throws IOException {
            //Files present in dest but not present in source have to be deleted
            Set<String> filesToBeDeleted = Sets.difference(
                    ImmutableSet.copyOf(local.listAll()),
                    ImmutableSet.copyOf(remote.listAll())
            );

            for (String fileName : filesToBeDeleted){
                local.deleteFile(fileName);
            }

            if(!filesToBeDeleted.isEmpty()) {
                log.debug("Following files have been removed from Lucene " +
                        "index directory [{}]", filesToBeDeleted);
            }
        }

        private class FileReference {
            final String name;
            private volatile boolean valid;

            private FileReference(String name) {
                this.name = name;
            }

            boolean isLocalValid(){
                return valid;
            }

            IndexInput openLocalInput( IOContext context) throws IOException {
                return local.openInput(name, context);
            }

            void markValid(){
                this.valid = true;
            }
        }
    }
}
