package io.mixeway.rest.utils;

import io.mixeway.db.entity.CodeProject;
import io.mixeway.db.entity.Interface;
import io.mixeway.db.entity.Project;
import io.mixeway.db.entity.WebApp;
import io.mixeway.db.repository.CodeVulnRepository;
import io.mixeway.db.repository.InfrastructureVulnRepository;
import io.mixeway.db.repository.InterfaceRepository;
import io.mixeway.db.repository.WebAppVulnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.mixeway.config.Constants;

@Component
public class ProjectRiskAnalyzer {
    private static final int CODE_CRITIC_WAGE = 10;
    private static final int CODE_HIGH_WAGE = 3;
    private static final int WEBAPP_HIGH_WAGE = 8;
    private static final int WEBAPP_MEDIUM_WAGE = 2;
    private static final int INFRA_HIGH_WAGE = 7;
    private static final int INFRA_MEDIUM_WAGE = 1;
    private static final Logger log = LoggerFactory.getLogger(ProjectRiskAnalyzer.class);
    private final CodeVulnRepository codeVulnRepository;
    private final InfrastructureVulnRepository infrastructureVulnRepository;
    private final WebAppVulnRepository webAppVulnRepository;
    private final InterfaceRepository interfaceRepository;

    @Autowired
    public ProjectRiskAnalyzer (CodeVulnRepository codeVulnRepository,
                         InfrastructureVulnRepository infrastructureVulnRepository,
                         WebAppVulnRepository webAppVulnRepository,
                         InterfaceRepository interfaceRepository){
        this.infrastructureVulnRepository = infrastructureVulnRepository;
        this.codeVulnRepository = codeVulnRepository;
        this.webAppVulnRepository = webAppVulnRepository;
        this.interfaceRepository = interfaceRepository;
    }

    public int getProjectCodeRisk(Project project) {
        return codeVulnRepository.countRiskForProject(project.getId(),CODE_CRITIC_WAGE, CODE_HIGH_WAGE,Constants.FORTIFY_ANALYSIS_EXPLOITABLE);
    }
    public int getProjectInfraRisk(Project project){
        return infrastructureVulnRepository.countRiskForProject(project.getId(),INFRA_HIGH_WAGE,INFRA_HIGH_WAGE,INFRA_MEDIUM_WAGE);
    }
    public int getProjectWebAppRisk(Project project){
        return webAppVulnRepository.countRiskForProject(project.getId(),WEBAPP_HIGH_WAGE,WEBAPP_HIGH_WAGE,WEBAPP_MEDIUM_WAGE);
    }
    public int getProjectAuditRisk(Project project){
        return 0;
    }

    public int getInterfaceRisk(Interface i) {
        return infrastructureVulnRepository.countRiskForInterface(i.getId(),INFRA_HIGH_WAGE,INFRA_HIGH_WAGE, INFRA_MEDIUM_WAGE);
    }
    public int getWebAppRisk(WebApp webApp){
        return webAppVulnRepository.countRiskForWebApp(webApp.getId(),WEBAPP_HIGH_WAGE,WEBAPP_HIGH_WAGE,WEBAPP_MEDIUM_WAGE);
    }
    public int getCodeProjectRisk(CodeProject cp){
        int result = 0;
        result += codeVulnRepository.getCountByCodeProjectIdSeverityAndAnalysis(cp.getId(),Constants.VULN_CRITICALITY_CRITICAL, Constants.FORTIFY_ANALYSIS_EXPLOITABLE) * CODE_CRITIC_WAGE;
        result += codeVulnRepository.getCountByCodeProjectIdSeverityAndAnalysis(cp.getId(),Constants.VULN_CRITICALITY_HIGH, Constants.FORTIFY_ANALYSIS_EXPLOITABLE) * CODE_HIGH_WAGE;
        return codeVulnRepository.countRiskForCodeProject(cp.getId(),CODE_CRITIC_WAGE,CODE_HIGH_WAGE,Constants.FORTIFY_ANALYSIS_EXPLOITABLE);
    }
}
