/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal.operations;

public class OperationIdentifierRegistry {
    private static ThreadLocal<OperationIdentifier> operationIdentifierThreadLocal = new ThreadLocal<OperationIdentifier>();
    private static volatile OperationIdentifier parentOperationIdentifier;

    public static OperationIdentifier getCurrentOperationIdentifier() {
        OperationIdentifier operationId = operationIdentifierThreadLocal.get();
        if (operationId == null) {
            operationId = parentOperationIdentifier;
        }
        return operationId;
    }

    public static void setCurrentOperationIdentifier(OperationIdentifier operationId) {
        if (operationId == null) {
            operationIdentifierThreadLocal.remove();
        } else {
            operationIdentifierThreadLocal.set(operationId);
        }
    }

    public static void setParentOperationIdentifier(OperationIdentifier operationId) {
        parentOperationIdentifier = operationId;
    }
}
