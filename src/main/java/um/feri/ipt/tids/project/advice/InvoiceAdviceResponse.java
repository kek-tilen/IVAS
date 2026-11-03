package um.feri.ipt.tids.project.advice;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

public record InvoiceAdviceResponse(Metadata metadata, LocalEvaluation localChecks, Advice advice) {
    public record Metadata(String provider, String deployment, String model, String generatedAt) { }
    public record LocalCheck(String code, String status, String message) { }
    public record LocalEvaluation(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal calculatedLineNetTotal,
                                  @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expectedGrossTotal,
                                  List<LocalCheck> checks) { }
    public record Advice(String summary, List<String> observations,
                         List<String> recommendedActions, List<String> missingInformation) { }
}
