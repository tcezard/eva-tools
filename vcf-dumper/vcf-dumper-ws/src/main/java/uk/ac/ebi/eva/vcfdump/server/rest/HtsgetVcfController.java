/*
 * Copyright 2017 EMBL - European Bioinformatics Institute
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
package uk.ac.ebi.eva.vcfdump.server.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import uk.ac.ebi.eva.commons.core.models.Region;
import uk.ac.ebi.eva.commons.mongodb.services.VariantSourceService;
import uk.ac.ebi.eva.commons.mongodb.services.VariantWithSamplesAndAnnotationsService;
import uk.ac.ebi.eva.vcfdump.QueryParams;
import uk.ac.ebi.eva.vcfdump.VariantExporterController;
import uk.ac.ebi.eva.vcfdump.server.configuration.DBAdaptorConnector;
import uk.ac.ebi.eva.vcfdump.server.configuration.MultiMongoDbFactory;
import uk.ac.ebi.eva.vcfdump.server.model.HtsGetError;
import uk.ac.ebi.eva.vcfdump.server.model.HtsGetResponse;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

@RestController
@RequestMapping(value = "/v1/variants/")
@Api(tags = {"htsget"})
public class HtsgetVcfController {

    private static final String VCF = "VCF";

    private Properties evaProperties;

    private VariantSourceService variantSourceService;

    private VariantWithSamplesAndAnnotationsService variantService;

    public HtsgetVcfController(VariantSourceService variantSourceService,
                               VariantWithSamplesAndAnnotationsService variantService) throws IOException {
        this.variantSourceService = variantSourceService;
        this.variantService = variantService;
        evaProperties = new Properties();
        evaProperties.load(VcfDumperWSServer.class.getResourceAsStream("/eva.properties"));
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.GET, consumes = "application/*",
            produces = "application/vnd.ga4gh.htsget.v0.2rc+json; charset=UTF-8")
    public ResponseEntity getHtsgetUrls(
            @ApiParam(value = "Study identifiers (separate with comma for multiple studies), e.g. PRJEB9799. " +
                    "Each individual identifier of studies can be looked up in " +
                    "https://www.ebi.ac.uk/eva/webservices/rest/v1/meta/studies/all in the field named 'id'.",
                    required = true)
            @PathVariable("id") String id,
            @ApiParam(value = "Format in which the data will be represented, e.g. VCF", defaultValue = "VCF")
            @RequestParam(name = "format", required = false) String format,
            @ApiParam(value = "Reference sequence name, e.g. 1 or chr1 or CM000001.1")
            @RequestParam(name = "referenceName", required = false) String referenceName,
            @ApiParam(value = "First letter of the genus, followed by the full species name, e.g. ecaballus_20. " +
                    "Allowed values can be looked up in https://www.ebi.ac.uk/eva/webservices/rest/v1/meta/species/list/" +
                    " concatenating the fields 'taxonomyCode' and 'assemblyCode' (separated by underscore).",
                    required = true)
            @RequestParam(name = "species", required = false) String species,
            @ApiParam(value = "Start position, e.g. 3000000")
            @RequestParam(name = "start", required = false) Long start,
            @ApiParam(value = "End position, e.g. 3010000")
            @RequestParam(name = "end", required = false) Long end,
            HttpServletRequest request) throws URISyntaxException {

        Optional<ResponseEntity> validationsResponse = validateParameters(format, referenceName, start, end);
        if (validationsResponse.isPresent()) {
            return validationsResponse.get();
        }

        String dbName = DBAdaptorConnector.getDBName(species);
        MultiMongoDbFactory.setDatabaseNameForCurrentThread(dbName);
        int blockSize = Integer.parseInt(evaProperties.getProperty("eva.htsget.blocksize"));
        VariantExporterController controller = new VariantExporterController(dbName, variantSourceService,
                                                                             variantService,
                                                                             Arrays.asList(id.split(",")),
                                                                             evaProperties, new QueryParams(),
                                                                             blockSize);

        if (start == null) {
            start = controller.getCoordinateOfFirstVariant(referenceName);
        }
        if (end == null) {
            end = controller.getCoordinateOfLastVariant(referenceName);
        }
        Optional<ResponseEntity> errorResponse = validateRequest(referenceName, start, end, controller);
        if (errorResponse.isPresent()) {
            return errorResponse.get();
        }

        List<Region> regionList = controller.divideChromosomeInChunks(referenceName, start, end);
        HtsGetResponse htsGetResponse = new HtsGetResponse(VCF, request.getServerName() + ":" + request.getServerPort(),
                                                           request.getContextPath(), id, referenceName, species,
                                                           regionList);
        return ResponseEntity.status(HttpStatus.OK).body(Collections.singletonMap("htsget", htsGetResponse));
    }

    private Optional<ResponseEntity> validateParameters(String format, String referenceName, Long start, Long end) {
        if (!VCF.equals(format)) {
            return Optional.of(getResponseEntity("UnsupportedFormat",
                                                 "The requested file format is not supported by the server"));
        }
        if (start != null && end != null && end <= start) {
            return Optional.of(getResponseEntity("InvalidRange", "The requested range cannot be satisfied"));
        }
        if (start != null && referenceName == null) {
            return Optional.of(getResponseEntity("InvalidInput", "Reference name is not specified when start is " +
                    "specified"));
        }
        if (referenceName == null) {
            return Optional.of(getResponseEntity("Unsupported", "'referenceName' is required"));
        }
        return Optional.empty();
    }

    private ResponseEntity getResponseEntity(String error, String message) {
        HtsGetError htsGetError = new HtsGetError(error, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonMap("htsget", htsGetError));
    }

    private Optional<ResponseEntity> validateRequest(String referenceName, Long start, Long end,
                                                     VariantExporterController controller) {
        if (start == null) {
            start = controller.getCoordinateOfFirstVariant(referenceName);
        }
        if (end == null) {
            end = controller.getCoordinateOfLastVariant(referenceName);
        }
        if (end <= start) {
            // Applies to valid requests such as chromosome 1, start: 1.000.000, end: empty.
            // If variants exist only in region 200.000 to 800.000, getCoordinateOfLastVariant() will return 800.000.
            // Given that 800.000 < 1.000.000, no region can be found.
            return Optional.of(getResponseEntity("NotFound", "The resource requested was not found"));
        }
        if (!controller.validateSpecies()) {
            return Optional.of(getResponseEntity("InvalidInput", "The requested species is not available"));
        }
        if (!controller.validateStudies()) {
            return Optional.of(getResponseEntity("InvalidInput", "The requested study(ies) is not available"));
        }
        return Optional.empty();
    }

    @RequestMapping(value = "/headers", method = RequestMethod.GET, produces = "application/octet-stream")
    public StreamingResponseBody getHtsgetHeaders(
            @ApiParam(value = "First letter of the genus, followed by the full species name, e.g. ecaballus_20. " +
                    "Allowed values can be looked up in https://www.ebi.ac.uk/eva/webservices/rest/v1/meta/species/list/" +
                    " concatenating the fields 'taxonomyCode' and 'assemblyCode' (separated by underscore).",
                    required = true)
            @RequestParam(name = "species") String species,
            @ApiParam(value = "Study identifiers (separate with comma for multiple studies), e.g. PRJEB9799. " +
                    "Each individual identifier of studies can be looked up in " +
                    "https://www.ebi.ac.uk/eva/webservices/rest/v1/meta/studies/all in the field named 'id'.",
                    required = true)
            @RequestParam(name = "studies") List<String> studies,
            HttpServletResponse response) {

        String dbName = DBAdaptorConnector.getDBName(species);
        StreamingResponseBody responseBody = getStreamingHeaderResponse(dbName, studies, evaProperties,
                                                                        new QueryParams(), response);
        return responseBody;
    }

    @RequestMapping(value = "/block", method = RequestMethod.GET, produces = "application/octet-stream")
    public StreamingResponseBody getHtsgetBlocks(
            @ApiParam(value = "First letter of the genus, followed by the full species name, e.g. ecaballus_20. " +
                    "Allowed values can be looked up in https://www.ebi.ac.uk/eva/webservices/rest/v1/meta/species/list/" +
                    " concatenating the fields 'taxonomyCode' and 'assemblyCode' (separated by underscore).",
                    required = true)
            @RequestParam(name = "species") String species,
            @ApiParam(value = "Study identifiers (separate with comma for multiple studies), e.g. PRJEB9799. " +
                    "Each individual identifier of studies can be looked up in " +
                    "https://www.ebi.ac.uk/eva/webservices/rest/v1/meta/studies/all in the field named 'id'.",
                    required = true)
            @RequestParam(name = "studies") List<String> studies,
            @ApiParam(value = "Comma separated genomic regions in the format chr:start-end. e.g. 1:3000000-3000999",
                    required = true)
            @RequestParam(name = "region") String chrRegion,
            HttpServletResponse response) {

        String dbName = DBAdaptorConnector.getDBName(species);
        QueryParams queryParameters = new QueryParams();
        queryParameters.setRegion(chrRegion);
        StreamingResponseBody responseBody = getStreamingBlockResponse(dbName, studies, evaProperties, queryParameters,
                                                                       response);
        return responseBody;
    }

    private StreamingResponseBody getStreamingHeaderResponse(String dbName, List<String> studies,
                                                             Properties evaProperties, QueryParams queryParameters,
                                                             HttpServletResponse response) {
        return outputStream -> {
            VariantExporterController controller;
            try {
                MultiMongoDbFactory.setDatabaseNameForCurrentThread(dbName);
                controller = new VariantExporterController(dbName, variantSourceService, variantService, studies,
                                                           outputStream, evaProperties, queryParameters);
                // tell the client that the file is an attachment, so it will download it instead of showing it
                response.addHeader(HttpHeaders.CONTENT_DISPOSITION,
                                   "attachment;filename=" + controller.getOutputFileName());
                controller.exportHeader();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private StreamingResponseBody getStreamingBlockResponse(String dbName, List<String> studies,
                                                            Properties evaProperties,
                                                            QueryParams queryParameters,
                                                            HttpServletResponse response) {
        return outputStream -> {
            VariantExporterController controller;
            try {
                MultiMongoDbFactory.setDatabaseNameForCurrentThread(dbName);
                controller = new VariantExporterController(dbName, variantSourceService,
                                                           variantService, studies, outputStream, evaProperties,
                                                           queryParameters);
                // tell the client that the file is an attachment, so it will download it instead of showing it
                response.addHeader(HttpHeaders.CONTENT_DISPOSITION,
                                   "attachment;filename=" + controller.getOutputFileName());
                controller.exportBlock();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
