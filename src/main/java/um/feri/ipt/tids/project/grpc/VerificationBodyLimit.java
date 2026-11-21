package um.feri.ipt.tids.project.grpc;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;

/** Bound invoice storage and verification JSON, including requests without Content-Length. */
@Component
public class VerificationBodyLimit extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean verification = request.getMethod().equals("POST") && path.startsWith("/api/v1/verification/");
        boolean invoices = java.util.Set.of("POST", "PATCH").contains(request.getMethod())
                && (path.equals("/api/v1/invoices") || path.matches("/api/v1/invoices/[0-9a-fA-F-]{36}"));
        boolean sources = request.getMethod().equals("POST") && path.startsWith("/api/v1/sources/");
        return !verification && !invoices && !sources;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        final int limit = 131072;
        byte[] body = request.getContentLengthLong() > limit ? null : request.getInputStream().readNBytes(limit + 1);
        if (body == null || body.length > limit) {
            response.setStatus(413); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"REQUEST_TOO_LARGE\",\"message\":\"Zahtevek presega 128 KiB.\"}");
            return;
        }
        var bytes = new ByteArrayInputStream(body);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return bytes.read(); }
                    @Override public int read(byte[] b, int off, int len) { return bytes.read(b, off, len); }
                    @Override public boolean isFinished() { return bytes.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        }, response);
    }
}
