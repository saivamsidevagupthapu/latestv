package com.stablespringbootproject.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.stablespringbootproject.Dto.*;
import com.stablespringbootproject.Entity.*;
import com.stablespringbootproject.repository.*;

@Service
public class Stableservice {

    private final RestTemplate restTemplate;
    private final Countryrepo countryRepo;
    private final Countryservicerepo stableRepo;
    private final Vendorrepo vendorRepo;
    private final Vendorapirepo vendorApiRepository;
    private final VendorJsonMappingrepo jsonMappingRepo;
    private final Vehiclerequestmappingrepo vehicleRequestMappingRepo;

    public Stableservice(RestTemplate restTemplate, Countryrepo countryRepo,
                         Countryservicerepo stableRepo, Vendorrepo vendorRepo,
                         Vendorapirepo vendorApiRepository,
                         VendorJsonMappingrepo jsonMappingRepo,
                         Vehiclerequestmappingrepo vehicleRequestMappingRepo) {

        this.restTemplate = restTemplate;
        this.countryRepo = countryRepo;
        this.stableRepo = stableRepo;
        this.vendorRepo = vendorRepo;
        this.vendorApiRepository = vendorApiRepository;
        this.jsonMappingRepo = jsonMappingRepo;
        this.vehicleRequestMappingRepo = vehicleRequestMappingRepo;
    }

    public Stableresponse fetchVehicle(Stablerequest request) {

        Countryentity country = countryRepo.findByCountryCode(request.getCountry())
                .orElseThrow(() -> new RuntimeException("Country Not Found"));

        Countryserviceentity service = stableRepo
                .findFirstByCountryCodeAndActiveTrue(country.getCountryCode())
                .orElseThrow(() -> new RuntimeException("No active service found"));

        Vendorentity vendor = vendorRepo.findByVendorNameIgnoreCaseAndPhoneNumber(
                request.getVendorname(), request.getPhone_number());

        if (vendor == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendor Not Found");
        }

        List<Vendorapis> vendorApis = vendorApiRepository
                .findByVendorIdAndApiType(vendor.getId(), request.getApi_usage_type());

        for (Vendorapis vendorApi : vendorApis) {

            List<vehiclerequestmapping> mappings =
                    vehicleRequestMappingRepo.findByVendorIdAndApiId(
                            vendor.getId(), vendorApi.getApiId());

            Map<String, Object> vendorResponse =
                    callVendor(service, vendorApi, mappings, request);

            if (vendorResponse != null) {

                List<Vehicleresponcemapping> responseMappings =
                        jsonMappingRepo.findByApiId(vendorApi.getApiId());

                Stableresponse response =
                        mapVendorResponse(vendorResponse, responseMappings);

                response.setCountry(country.getCountryCode());

                return response;
            }
        }

        throw new RuntimeException("Vehicle not found");
    }

    private Map<String, Object> callVendor(Countryserviceentity service,
                                           Vendorapis vendorApi,
                                           List<vehiclerequestmapping> mappings,
                                           Stablerequest request) {

        String url = service.getBaseUrl() + vendorApi.getApiUrl();

        Map<String, String> requestMap = convertRequestToMap(request);

        Map<String, String> pathVars = new HashMap<>();
        Map<String, String> queryParams = new HashMap<>();
        Map<String, String> headersMap = new HashMap<>();
        Map<String, Object> bodyJson = new HashMap<>();

        for (vehiclerequestmapping m : mappings) {

            String value = m.getConstantValue() != null && !m.getConstantValue().isEmpty()
                    ? m.getConstantValue()
                    : getIgnoreCase(requestMap, m.getStableField());

            if (value == null) continue;

            switch (m.getLocation()) {
                case PATH -> pathVars.put(m.getExternalName(), value);
                case QUERY -> queryParams.put(m.getExternalName(), value);
                case HEADER -> headersMap.put(m.getExternalName(), value);
                case BODY_JSON -> bodyJson.put(m.getExternalName(), value);
            }
        }

        url = resolveUrl(url, pathVars);

        if (!queryParams.isEmpty()) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
            queryParams.forEach(builder::queryParam);
            url = builder.toUriString();
        }

        HttpHeaders headers = new HttpHeaders();
        headersMap.forEach(headers::add);

        HttpMethod method = HttpMethod.valueOf(vendorApi.getHttpMethod().toUpperCase());

        HttpEntity<?> entity = method == HttpMethod.GET
                ? new HttpEntity<>(headers)
                : new HttpEntity<>(bodyJson, headers);

        ResponseEntity<Map> response =
                restTemplate.exchange(URI.create(url), method, entity, Map.class);

        return response.getBody();
    }

    private String resolveUrl(String url, Map<String, String> pathVars) {

        Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(url);

        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = getIgnoreCase(pathVars, key);

            matcher.appendReplacement(result,
                    URLEncoder.encode(value, StandardCharsets.UTF_8));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    // 🔥 UPDATED METHOD
    private Stableresponse mapVendorResponse(Map<String, Object> vendorResponse,
                                             List<Vehicleresponcemapping> mappings) {

        Stableresponse response = new Stableresponse();
        Map<String, String> vehicleDetails = new HashMap<>();

        if (mappings.isEmpty()) return response;

        Vehicleresponcemapping map = mappings.get(0);

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.convertValue(vendorResponse, JsonNode.class);

            JsonNode vehicleNode = findNodeByKey(rootNode, "vehicleinfo");
            JsonNode searchNode = vehicleNode != null ? vehicleNode : rootNode;

            for (Field field : map.getClass().getDeclaredFields()) {

                if (List.of("id", "apiId", "vendorId", "countryId")
                        .contains(field.getName())) continue;

                field.setAccessible(true);

                String internalKey = field.getName();
                Object externalKeyObj = field.get(map);

                if (externalKeyObj != null) {
                    String externalKey = externalKeyObj.toString();

                    String value = findValue(searchNode, externalKey);

                    if (value != null) {
                        vehicleDetails.put(internalKey, value);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        response.setVehicleDetails(vehicleDetails);
        return response;
    }

    // 🔥 RECURSIVE VALUE FINDER
    private String findValue(JsonNode node, String targetKey) {

        if (node == null) return null;

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();

                if (entry.getKey().equalsIgnoreCase(targetKey)
                        && entry.getValue().isValueNode()) {
                    return entry.getValue().asText();
                }

                String found = findValue(entry.getValue(), targetKey);
                if (found != null) return found;
            }
        }

        else if (node.isArray()) {
            for (JsonNode item : node) {
                String found = findValue(item, targetKey);
                if (found != null) return found;
            }
        }

        return null;
    }

    // 🔥 NODE FINDER
    private JsonNode findNodeByKey(JsonNode node, String targetKey) {

        if (node == null) return null;

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();

                if (entry.getKey().equalsIgnoreCase(targetKey)) {
                    return entry.getValue();
                }

                JsonNode found = findNodeByKey(entry.getValue(), targetKey);
                if (found != null) return found;
            }
        }

        else if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode found = findNodeByKey(item, targetKey);
                if (found != null) return found;
            }
        }

        return null;
    }

    private Map<String, String> convertRequestToMap(Object obj) {
        Map<String, String> map = new HashMap<>();

        try {
            for (Field field : obj.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value != null) {
                    map.put(field.getName(), value.toString());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return map;
    }

    private String getIgnoreCase(Map<String, String> map, String key) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
