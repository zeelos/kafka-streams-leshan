/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.zeelos.leshan.kafka.streams.utils;


import io.zeelos.leshan.avro.resource.AvroResource;
import io.zeelos.leshan.avro.response.AvroReadResponseBody;
import io.zeelos.leshan.avro.response.AvroResponseObserve;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Utils {

    public static AvroResource getResource(AvroResponseObserve response) {
        AvroReadResponseBody read = (AvroReadResponseBody) response.getRep().getBody();
        AvroResource resource = (AvroResource) read.getContent();
        return resource;
    }

    public static Double getValue(AvroResponseObserve response) {
        AvroResource resource = getResource(response);
        Object val = resource.getValue();
        if (val instanceof Long) {
            return ((Long) val).doubleValue();
        }
        return (Double) val;
    }

    public static Properties sslProperties(String sslConfigFile) {
        // load any ssl properties
        // TODO refactor for all types of properties

        // initialize empty.
        Properties sslProps = new Properties();
        try {
            InputStream is = new FileInputStream(sslConfigFile);
            sslProps.load(is);

        } catch (IOException e) {
            // ignore
        }

        return sslProps;
    }
}
