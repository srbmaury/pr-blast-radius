package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    @GetMapping("/example")
    public List<ImpactFinding> example() {
        return List.of(
                new ImpactFinding(
                        "refund-service",
                        "reads orders.status",
                        "static SQL reference",
                        ImpactConfidence.STRONG
                )
        );
    }
}
