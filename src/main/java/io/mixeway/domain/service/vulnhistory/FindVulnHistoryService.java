package io.mixeway.domain.service.vulnhistory;

import io.mixeway.api.protocol.OverAllVulnTrendChartData;
import io.mixeway.api.protocol.SourceDetectionChartData;
import io.mixeway.db.entity.Project;
import io.mixeway.db.repository.VulnHistoryRepository;
import io.mixeway.utils.PermissionFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author gsiewruk
 */
@Service
@RequiredArgsConstructor
public class FindVulnHistoryService {
    private final VulnHistoryRepository vulnHistoryRepository;
    private final PermissionFactory permissionFactory;

    public List<OverAllVulnTrendChartData> getVulnTrendData(Principal principal){
        return vulnHistoryRepository.getOverallVulnTrendData(permissionFactory.getProjectForPrincipal(principal).stream().map(Project::getId).collect(Collectors.toList()));
    }

    public SourceDetectionChartData getSourceTrendData(Principal principal){
        return vulnHistoryRepository.getSourceTrendChart(permissionFactory.getProjectForPrincipal(principal).stream().map(Project::getId).collect(Collectors.toList()));
    }
}
