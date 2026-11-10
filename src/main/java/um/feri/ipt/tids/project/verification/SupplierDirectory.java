package um.feri.ipt.tids.project.verification;

import java.util.function.BooleanSupplier;
import um.feri.ipt.tids.project.grpc.v1.*;

/** Boundary for a registry adapter; test implementations never access the external ARES site. */
public interface SupplierDirectory {
    SupplierLookupResult lookup(Supplier supplier, BooleanSupplier cancelled);
}
