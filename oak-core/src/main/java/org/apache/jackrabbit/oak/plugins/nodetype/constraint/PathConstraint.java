/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.nodetype.constraint;

import java.util.function.Predicate;

import javax.jcr.RepositoryException;
import javax.jcr.Value;

import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.core.GuavaDeprecation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PathConstraint implements Predicate<Value>, com.google.common.base.Predicate<Value> {
    private static final Logger log = LoggerFactory.getLogger(PathConstraint.class);

    private final String requiredValue;

    public PathConstraint(String definition) {
        this.requiredValue = definition;
    }

    @Override
    public boolean test(@Nullable Value value) {
        try {
            if (value == null || requiredValue == null) {
                return false;
            }

            if ("*".equals(requiredValue)) {
                return true;
            }

            String actual = value.getString();
            String required = requiredValue;
            if (required.endsWith("/*")) {
                required = required.substring(0, required.length() - 2);
                if (PathUtils.isAncestor(required, actual)) {
                    return true;
                }
            }

            return required.equals(actual);
        }
        catch (RepositoryException e) {
            log.warn("Error checking path constraint " + this, e);
            return false;
        }
    }

    /**
     * @deprecated use {@link #test(Value)} instead  (see <a href="https://issues.apache.org/jira/browse/OAK-8874">OAK-8874</a>)
     */
    @Deprecated(since = "1.26.0", forRemoval = true)
    @Override
    public boolean apply(@Nullable Value value) {
        GuavaDeprecation.handleCall("OAK-8874");
        return test(value);
    }

    @Override
    public String toString() {
        return '\'' + requiredValue + '\'';
    }

}
