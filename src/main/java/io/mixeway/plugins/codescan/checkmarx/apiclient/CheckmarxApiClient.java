package io.mixeway.plugins.codescan.checkmarx.apiclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.HeaderColumnNameTranslateMappingStrategy;
import io.mixeway.config.Constants;
import io.mixeway.db.entity.*;
import io.mixeway.db.entity.Scanner;
import io.mixeway.db.repository.*;
import io.mixeway.plugins.codescan.checkmarx.model.*;
import io.mixeway.plugins.codescan.model.CodeRequestHelper;
import io.mixeway.plugins.codescan.model.TokenValidator;
import io.mixeway.plugins.codescan.service.CodeScanClient;
import io.mixeway.pojo.SecureRestTemplate;
import io.mixeway.pojo.SecurityScanner;
import io.mixeway.rest.model.ScannerModel;
import io.mixeway.rest.project.model.SASTProject;
import org.codehaus.jettison.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponseSupport;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.io.StringReader;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class CheckmarxApiClient implements CodeScanClient, SecurityScanner {
    DateTimeFormatter sdf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Logger log = LoggerFactory.getLogger(CheckmarxApiClient.class);
    private final ScannerTypeRepository scannerTypeRepository;
    private final ScannerRepository scannerRepository;
    private final VaultOperations operations;
    private final SecureRestTemplate secureRestTemplate;
    private final CodeGroupRepository codeGroupRepository;
    private final CodeProjectRepository codeProjectRepository;
    private final ProxiesRepository proxiesRepository;
    private TokenValidator tokenValidator = new TokenValidator();
    @Autowired
    CheckmarxApiClient(ScannerTypeRepository scannerTypeRepository, ScannerRepository scannerRepository,
                       CodeProjectRepository codeProjectRepository, ProxiesRepository proxiesRepository,
                       VaultOperations operations, SecureRestTemplate secureRestTemplate, CodeGroupRepository codeGroupRepository){
        this.operations = operations;
        this.scannerRepository = scannerRepository;
        this.proxiesRepository = proxiesRepository;
        this.scannerTypeRepository = scannerTypeRepository;
        this.codeProjectRepository = codeProjectRepository;
        this.codeGroupRepository = codeGroupRepository;
        this.secureRestTemplate = secureRestTemplate;
    }
    @Override
    public void loadVulnerabilities(Scanner scanner, CodeGroup codeGroup, String urlToGetNext, Boolean single, CodeProject codeProject, List<CodeVuln> codeVulns) throws ParseException, JSONException, CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException {
        downloadResultsForScan(scanner,codeProject, codeGroup);
    }

    @Override
    public Boolean runScan(CodeGroup cg, CodeProject codeProject) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException, JSONException, ParseException {
        Optional<io.mixeway.db.entity.Scanner> cxSast = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_CHECKMARX)).stream().findFirst();
        if (cxSast.isPresent()){
            return createProjectGitLink(cxSast.get(),codeProject) && createScan(cxSast.get(),codeProject);
        } else {
            return false;
        }
    }

    @Override
    public boolean isScanDone(CodeGroup cg) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException, ParseException, JSONException {
        Optional<io.mixeway.db.entity.Scanner> cxSast = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_CHECKMARX)).stream().findFirst();
        if (cxSast.isPresent()) {
            return getScanInfo(cxSast.get(), cg).getStatus().getName().equals(Constants.CX_STATUS_FINISHED)
                    && generateReport(cxSast.get(), cg)
                    && checkReportState(cxSast.get(), cg);
        } else
            return false;
    }

    @Override
    public boolean canProcessRequest(CodeGroup cg) {
        Optional<io.mixeway.db.entity.Scanner> cxSast = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_CHECKMARX)).stream().findFirst();
        return cxSast.isPresent();
    }

    @Override
    public boolean initialize(Scanner scanner) throws JSONException, ParseException, CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException, JAXBException, Exception {

        return generateToken(scanner) && getTeam(scanner);
    }

    @Override
    public boolean canProcessRequest(Scanner scanner) {
        return scanner.getScannerType().getName().equals(Constants.SCANNER_TYPE_CHECKMARX);
    }

    @Override
    public boolean canProcessRequest(ScannerType scannerType) {
        return scannerType.getName().equals(Constants.SCANNER_TYPE_CHECKMARX);
    }

    @Override
    public void saveScanner(ScannerModel scannerModel) throws Exception {
        List<Scanner>  scanners = scannerRepository.findByScannerTypeIn(scannerTypeRepository.getCodeScanners());
        Optional<Proxies> proxies = proxiesRepository.findById(scannerModel.getProxy());
        if (scanners.stream().findFirst().isPresent()){
            throw new Exception(Constants.SAST_SCANNER_ALREADY_REGISTERED);
        } else {
            ScannerType scannerType = scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_CHECKMARX);
            Scanner checkmarx = new io.mixeway.db.entity.Scanner();
            checkmarx.setApiUrl(scannerModel.getApiUrl());
            checkmarx.setPassword(UUID.randomUUID().toString());
            checkmarx.setUsername(scannerModel.getUsername());
            checkmarx.setStatus(false);
            checkmarx.setScannerType(scannerType);
            if (proxies.isPresent())
                checkmarx.setProxies(proxies.get());
            // api key put to vault
            Map<String, String> passwordKeyMap = new HashMap<>();
            passwordKeyMap.put("password", scannerModel.getPassword());
            operations.write("secret/" + checkmarx.getPassword(), passwordKeyMap);
            scannerRepository.save(checkmarx);
        }

    }

    /**
     * Function calling Checkmarx rest API login function
     *
     * @param scanner
     */
    private boolean generateToken(io.mixeway.db.entity.Scanner scanner) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException, JSONException, ParseException {
        try {
            MultiValueMap<String, String> formEncodedForLogin = createFormForLogin(scanner);
            RestTemplate restTemplate = secureRestTemplate.noVerificationClient(scanner);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(formEncodedForLogin, headers);
            String API_GET_TOKEN = "/cxrestapi/auth/identity/connect/token";
            ResponseEntity<CxLoginResponse> response = restTemplate.exchange(scanner.getApiUrl() + API_GET_TOKEN, HttpMethod.POST, entity, CxLoginResponse.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                Date dt = new Date();
                LocalDateTime ldt = LocalDateTime.now().plusSeconds(Objects.requireNonNull(response.getBody()).getExpires_in());
                scanner.setFortifytokenexpiration(ldt.format(sdf));
                scanner.setFortifytoken(response.getBody().getAccess_token());
                if(!scanner.getStatus()){
                    scanner.setStatus(true);
                }
                scannerRepository.save(scanner);
                return true;
            } else {
                log.error("Checkmarx Authorization failure");
                return false;
            }
        } catch (HttpClientErrorException e){
            log.error("Error during getting teams from Checkmarx - {}", e.getLocalizedMessage());
            return false;
        }


    }

    private MultiValueMap<String, String> createFormForLogin(Scanner scanner) {
        VaultResponseSupport<Map<String, Object>> password = operations.read("secret/" + scanner.getPassword());
        assert password != null;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(Constants.CHECKMARX_LOGIN_FORM_USERNAME, scanner.getUsername());
        form.add(Constants.CHECKMARX_LOGIN_FORM_PASSWORD, Objects.requireNonNull(password.getData()).get("password").toString());
        form.add(Constants.CHECKMARX_LOGIN_FORM_GRANT_TYPE, Constants.CHECKMARX_LOGIN_FORM_GRANT_TYPE_VALUE);
        form.add(Constants.CHECKMARX_LOGIN_FORM_SCOPE,Constants.CHECKMARX_LOGIN_FORM_SCOPE_VALUE);
        form.add(Constants.CHECKMARX_LOGIN_FORM_CLIENTID, Constants.CHECKMARX_LOGIN_FORM_CLIENTID_VALUE);
        form.add(Constants.CHECKMARX_LOGIN_FORM_CLIENTSECRET, Constants.CHECKMARX_LOGIN_FORM_CLIENTSECRET_VALUE);
        return form;
    }
    private CodeRequestHelper prepareRestTemplate(Scanner scanner) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        if (tokenValidator.isTokenValid(scanner.getFortifytoken(), LocalDateTime.parse(scanner.getFortifytokenexpiration(), sdf))) {
            generateToken(scanner);
        }
        RestTemplate restTemplate = secureRestTemplate.noVerificationClient(scanner);
        HttpHeaders headers = new HttpHeaders();
        headers.set(Constants.HEADER_AUTHORIZATION, Constants.BEARER_TOKEN + " " + scanner.getFortifytoken());
        HttpEntity entity = new HttpEntity(headers);

        return new CodeRequestHelper(restTemplate,entity);
    }
    private boolean getTeam(Scanner scanner) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
        try {
            ResponseEntity<List<CxTeamResponse>> response = codeRequestHelper
                    .getRestTemplate()
                    .exchange(scanner.getApiUrl() + Constants.CX_GET_TEAMS_API, HttpMethod.GET, codeRequestHelper.getHttpEntity(), new ParameterizedTypeReference<List<CxTeamResponse>>() {});
            if (response.getStatusCode().equals(HttpStatus.OK) && Objects.requireNonNull(response.getBody()).stream().findFirst().isPresent()) {
                scanner.setTeam(Objects.requireNonNull(response.getBody()).stream().findFirst().get().getId());
                scannerRepository.save(scanner);
                return true;
            }
        } catch (HttpClientErrorException e){
            log.error("Error during getting teams from Checkmarx - {}", e.getLocalizedMessage());
        }
        return false;
    }
    @Override
    public List<SASTProject> getProjects(Scanner scanner) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
        List<SASTProject> sastProjects = new ArrayList<>();
        try {
            ResponseEntity<List<CxProject>> response = codeRequestHelper
                    .getRestTemplate()
                    .exchange(scanner.getApiUrl() + Constants.CX_GET_PROJECTS_API, HttpMethod.GET, codeRequestHelper.getHttpEntity(), new ParameterizedTypeReference<List<CxProject>>() {});
            if (response.getStatusCode().equals(HttpStatus.OK) && Objects.requireNonNull(response.getBody()).stream().findFirst().isPresent()) {
                for (CxProject project : response.getBody()){
                    sastProjects.add(new SASTProject(project.getId(),project.getName()));
                }
            }
        } catch (HttpClientErrorException e){
            log.error("Error during loading projects from Checkmarx - {}", e.getLocalizedMessage());
        }
        return sastProjects;
    }
    @Override
    public boolean createProject(Scanner scanner, CodeProject codeProject) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
        try {
            ObjectMapper objectMapper = new ObjectMapper();

            String objJackson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(new CxProjectCreate(codeProject.getCodeGroup().getName(), scanner));
            log.info("trying to creat project {}", objJackson);

            ResponseEntity<CxResponseId> response = codeRequestHelper
                    .getRestTemplate()
                    .exchange(scanner.getApiUrl() + Constants.CX_CREATE_PROJECT_API, HttpMethod.POST,
                            new HttpEntity<>(new CxProjectCreate(codeProject.getCodeGroup().getName(), scanner),codeRequestHelper.getHttpEntity().getHeaders()),
                            CxResponseId.class);
            if (response.getStatusCode().equals(HttpStatus.CREATED) ) {
                codeProject.getCodeGroup().setVersionIdAll((int) Objects.requireNonNull(response.getBody()).getId());
                codeGroupRepository.save(codeProject.getCodeGroup());
                log.info("CX - Successfull project creation for {}", codeProject.getCodeGroup().getName());
                return true;
            }
        } catch (HttpClientErrorException e){
            log.error("Error during loading projects from Checkmarx - {}", e.getLocalizedMessage());
        }
        return false;
    }

    private boolean createProjectGitLink(Scanner scanner, CodeProject codeProject) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
        try {
            VaultResponseSupport<Map<String, Object>> password = operations.read("secret/" + codeProject.getCodeGroup().getRepoPassword());
            assert password != null;
            ResponseEntity<String> response = codeRequestHelper
                    .getRestTemplate()
                    .exchange(scanner.getApiUrl() + Constants.CX_CREATE_GIT_FOR_PROJECT_API.replace(Constants.CX_PROJECTID, String.valueOf(codeProject.getCodeGroup().getVersionIdAll())), HttpMethod.POST,
                            new HttpEntity<>(new CxGitCreate(codeProject,Objects.requireNonNull(password.getData()).get("password").toString()),codeRequestHelper.getHttpEntity().getHeaders()),
                            String.class);
            if (response.getStatusCode().equals(HttpStatus.NO_CONTENT) || response.getStatusCode().equals(HttpStatus.OK)) {
                log.info("CX - Successfull set GIT for {}", codeProject.getName());
                return true;
            }
        } catch (HttpClientErrorException e){
            log.error("Error during loading projects from Checkmarx - {}", e.getLocalizedMessage());
        }
        return false;
    }
    private boolean createScan(Scanner scanner, CodeProject codeProject) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
        try {
            ResponseEntity<CxResponseId> response = codeRequestHelper
                    .getRestTemplate()
                    .exchange(scanner.getApiUrl() + Constants.CX_CREATE_SCAN_API, HttpMethod.POST,
                            new HttpEntity<>(new CxCreateScan(codeProject),codeRequestHelper.getHttpEntity().getHeaders()),
                            CxResponseId.class);
            if (response.getStatusCode().equals(HttpStatus.CREATED) ) {
                codeProject.setRunning(true);
                codeProject.getCodeGroup().setRunning(true);
                codeProject.getCodeGroup().setScanid(Long.toString(Objects.requireNonNull(response.getBody()).getId()));
                codeGroupRepository.save(codeProject.getCodeGroup());
                codeProjectRepository.save(codeProject);
                log.info("CX - Successfull Created and started scan for {}", codeProject.getName());
                return true;
            }
        } catch (HttpClientErrorException e){
            log.error("Error during loading projects from Checkmarx - {}", e.getLocalizedMessage());
        }
        return false;
    }
    private CxScan getScanInfo(Scanner scanner, CodeGroup codeGroup) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
        try {
            ResponseEntity<CxScan> response = codeRequestHelper
                    .getRestTemplate()
                    .exchange(scanner.getApiUrl() + Constants.CX_GET_SCAN_API.replace(Constants.CX_SCANID, codeGroup.getScanid()), HttpMethod.GET,
                            codeRequestHelper.getHttpEntity(),
                           CxScan.class);
            if (response.getStatusCode().equals(HttpStatus.OK) ) {
                return response.getBody();
            }
        } catch (HttpClientErrorException e){
            log.error("Error during loading projects from Checkmarx - {}", e.getLocalizedMessage());
        }
        return null;
    }
    private boolean generateReport(Scanner scanner, CodeGroup codeGroup) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
        try {
            ResponseEntity<CxResponseId> response = codeRequestHelper
                    .getRestTemplate()
                    .exchange(scanner.getApiUrl() + Constants.CX_CREATE_SCAN_API, HttpMethod.POST,
                            new HttpEntity<>(new CxReportGenerate(codeGroup),codeRequestHelper.getHttpEntity().getHeaders()),
                            CxResponseId.class);
            if (response.getStatusCode().equals(HttpStatus.ACCEPTED) ) {
                codeGroup.setJobId(String.valueOf(Objects.requireNonNull(response.getBody()).getReportId()));
                codeGroupRepository.save(codeGroup);
                log.info("CX - Successfull genarate report  for {}", codeGroup.getName());
                return true;
            }
        } catch (HttpClientErrorException e){
            log.error("Error during loading projects from Checkmarx - {}", e.getLocalizedMessage());
        }
        return false;
    }
    private boolean checkReportState(Scanner scanner, CodeGroup codeGroup) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
        try {
            ResponseEntity<CxReportStatus> response = codeRequestHelper
                    .getRestTemplate()
                    .exchange(scanner.getApiUrl() + Constants.CX_GET_REPORT_STATUS_API.replace(Constants.CX_REPORTID,codeGroup.getJobId()), HttpMethod.GET,
                            codeRequestHelper.getHttpEntity(),
                            CxReportStatus.class);
            if (response.getStatusCode().equals(HttpStatus.OK) ) {
                if (response.getBody().getStatus().getName().equals(Constants.CX_STATUS_FINISHED)){
                    log.info("CX - Successfull genarate report  for {}", codeGroup.getName());
                    codeGroup.setRunning(false);
                    codeGroupRepository.saveAndFlush(codeGroup);
                    for (CodeProject codeProject : codeGroup.getProjects()){
                        codeProject.setRunning(false);
                        codeProjectRepository.saveAndFlush(codeProject);
                    }
                    return true;
                }

            }
        } catch (HttpClientErrorException e){
            log.error("Error during loading projects from Checkmarx - {}", e.getLocalizedMessage());
        }
        return false;
    }
    private void downloadResultsForScan(Scanner scanner, CodeProject codeProject, CodeGroup codeGroup) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
        CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
        try {
            ResponseEntity<String> response = codeRequestHelper
                    .getRestTemplate()
                    .exchange(scanner.getApiUrl() + Constants.CX_GET_RESULTS_API.replace(Constants.CX_REPORTID,codeGroup.getJobId()), HttpMethod.GET,
                            codeRequestHelper.getHttpEntity(),
                            String.class);
            if (response.getStatusCode().equals(HttpStatus.OK) ) {
                processCsvReport(response.getBody(),codeProject);
                log.info("CX - Successfull processed report for {}", codeProject.getName());
            }
        } catch (HttpClientErrorException e){
            log.error("Error during loading projects from Checkmarx - {}", e.getLocalizedMessage());
        }
    }

    private void processCsvReport(String body, CodeProject codeProject) {
        Map<String, String> mapping = new
                HashMap<String, String>();
        mapping.put(Constants.CX_REPORT_QUERY, "query");
        mapping.put(Constants.CX_REPORT_DSTFILE, "dstLocation");
        mapping.put(Constants.CX_REPORT_DSTLINENO, "dstLine");
        mapping.put(Constants.CX_REPORT_ANALYSIS, "analysis");
        mapping.put(Constants.CX_REPORT_SEVERITY, "severity");
        mapping.put(Constants.CX_REPORT_DESCRIPTION, "description");
        mapping.put(Constants.CX_REPORT_STATE, "state");
        // HeaderColumnNameTranslateMappingStrategy
        // for Student class
        HeaderColumnNameTranslateMappingStrategy<CxResult> strategy =
                new HeaderColumnNameTranslateMappingStrategy<CxResult>();
        strategy.setType(CxResult.class);
        strategy.setColumnMapping(mapping);
        CSVReader csvReader = null;
        csvReader = new CSVReader(new StringReader(body));
        CsvToBean csvToBean = new CsvToBean();
        List<CxResult> results = csvToBean.parse(strategy, csvReader);
        log.info("get {} results", results.size());
    }


}
