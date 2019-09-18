/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.jmap.methods;

import java.io.IOException;

import javax.inject.Inject;

import org.apache.james.jmap.json.ObjectMapperFactory;
import org.apache.james.jmap.model.InvocationRequest;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JmapRequestParserImpl implements JmapRequestParser {

    private final ObjectMapper objectMapper;

    @Inject
    public JmapRequestParserImpl(ObjectMapperFactory objectMapperFactory) {
        this.objectMapper = objectMapperFactory.forParsing();
    }

    @Override
    public <T extends JmapRequest> T extractJmapRequest(InvocationRequest request, Class<T> requestClass)
            throws IOException, JsonParseException, JsonMappingException {
        return objectMapper.readValue(request.getParameters().toString(), requestClass);
    }
}