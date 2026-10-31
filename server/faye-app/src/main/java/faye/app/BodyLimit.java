package faye.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps what a browser may post to the screen API before anyone is signed in (ADR 0007, security
 * standard 3): a declared length over {@link #MAX_BYTES} is refused with 413 at once, and a body
 * without one (chunked) stops being read there, so the JSON reader refuses it. No screen answer
 * comes near the cap; a passkey assertion, the largest, is a few KiB.
 */
@Component
class BodyLimit extends OncePerRequestFilter {

    static final int MAX_BYTES = 64 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/flows/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_submission\"}");
            return;
        }
        chain.doFilter(new Bounded(request), response);
    }

    /** A request whose body ends after {@link #MAX_BYTES}, whatever the client sends. */
    private static final class Bounded extends HttpServletRequestWrapper {
        Bounded(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    if (read >= MAX_BYTES) throw new IOException("request body over " + MAX_BYTES + " bytes");
                    int b = in.read();
                    if (b >= 0) read++;
                    return b;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    if (read >= MAX_BYTES) throw new IOException("request body over " + MAX_BYTES + " bytes");
                    int n = in.read(buffer, off, (int) Math.min(len, MAX_BYTES - read));
                    if (n > 0) read += n;
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    in.setReadListener(listener);
                }
            };
        }
    }
}
