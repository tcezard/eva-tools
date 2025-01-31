/*
 * European Variation Archive (EVA) - Open-access database of all types of genetic
 * variation data from all species
 *
 * Copyright 2014-2017 EMBL - European Bioinformatics Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.ebi.eva.vcfdump.configuration;

import com.mongodb.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class DBAdaptorConnector {

    @Autowired
    private SpringDataMongoDbProperties springDataMongoDbProperties;

    /**
     * Get a MongoClient using the configuration (credentials) in a given Properties.
     *
     * @param springDataMongoDbProperties can have the next values:
     *                   - eva.mongo.auth.db authentication database
     *                   - eva.mongo.host comma-separated strings of colon-separated host and port strings: host_1:port_1,host_2:port_2
     *                   - eva.mongo.user
     *                   - eva.mongo.passwd
     *                   - eva.mongo.read-preference string, "secondaryPreferred" if unspecified. one of:
     *                          [primary, primaryPreferred, secondary, secondaryPreferred, nearest]
     * @return MongoClient with given credentials
     * @throws UnknownHostException
     */
    public static MongoClient getMongoClient(
            SpringDataMongoDbProperties springDataMongoDbProperties) throws UnknownHostException {

        String[] hosts = springDataMongoDbProperties.getHost().split(",");
        List<ServerAddress> servers = new ArrayList<>();

        // Get the list of hosts (optionally including the port number)
        for (String host : hosts) {
            String[] params = host.split(":");
            if (params.length > 1) {
                servers.add(new ServerAddress(params[0], Integer.parseInt(params[1])));
            } else {
                servers.add(new ServerAddress(params[0], 27017));
            }
        }

        String readPreference = springDataMongoDbProperties.getReadPreference();
        readPreference = readPreference == null || readPreference.isEmpty()? "secondaryPreferred" : readPreference;

        MongoClientOptions options = MongoClientOptions.builder()
                                                       .readPreference(ReadPreference.valueOf(readPreference))
                                                       .build();

        List<MongoCredential> mongoCredentialList = new ArrayList<>();
        String authenticationDb = springDataMongoDbProperties.getAuthenticationDatabase();
        if (authenticationDb != null && !authenticationDb.isEmpty()) {
            MongoCredential mongoCredential = MongoCredential.createCredential(
                    springDataMongoDbProperties.getUsername(),
                    authenticationDb,
                    springDataMongoDbProperties.getPassword().toCharArray());
            String authenticationMechanism = springDataMongoDbProperties.getAuthenticationMechanism();
            if (authenticationMechanism == null) {
                return new MongoClient(servers, options);
            }
            mongoCredential = mongoCredential.withMechanism(
                    AuthenticationMechanism.fromMechanismName(authenticationMechanism));
            mongoCredentialList = Collections.singletonList(mongoCredential);
        }

        return new MongoClient(servers, mongoCredentialList, options);
    }

    public static String getDBName(String species) {
        return "eva_" + species;
    }
}
