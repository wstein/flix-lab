import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal Debug Adapter Protocol (DAP) server that debugs a running Flix
 * program by attaching to its JVM over JDWP and resolving breakpoints
 * against .flix source lines via the "Flix" stratum that a fork of Flix
 * (github.com/wstein/flix-fork, --Xdebug) embeds as a JSR-45
 * SourceDebugExtension.
 *
 * This intentionally supports only the handful of requests needed to set a
 * line breakpoint, hit it, and inspect the call stack/locals -- it is not a
 * general-purpose Java debugger.
 */
public class FlixDebugAdapter {

    public static void main(String[] args) throws Exception {
        new FlixDebugAdapter().run();
    }

    // ------------------------------------------------------------------
    // DAP transport
    // ------------------------------------------------------------------

    private final InputStream in = System.in;
    private final PrintStream out;
    private final AtomicInteger seq = new AtomicInteger(1);

    FlixDebugAdapter() throws UnsupportedEncodingException {
        this.out = new PrintStream(System.out, true, "UTF-8");
    }

    private void run() throws IOException {
        while (true) {
            Map<String, Object> msg = readMessage();
            if (msg == null) return;
            String type = (String) msg.get("type");
            if ("request".equals(type)) {
                handleRequest(msg);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMessage() throws IOException {
        int contentLength = -1;
        while (true) {
            String line = readHeaderLine();
            if (line == null) return null;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(value);
                }
            }
        }
        if (contentLength < 0) return null;
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(body, read, contentLength - read);
            if (n < 0) return null;
            read += n;
        }
        String json = new String(body, StandardCharsets.UTF_8);
        Object parsed = Json.parse(json);
        return (Map<String, Object>) parsed;
    }

    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                byte[] bytes = buf.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(c);
            prev = c;
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }

    private synchronized void send(Map<String, Object> msg) {
        msg.put("seq", seq.getAndIncrement());
        String json = Json.write(msg);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.print("Content-Length: " + bytes.length + "\r\n\r\n");
        out.write(bytes, 0, bytes.length);
        out.flush();
    }

    private void sendResponse(Map<String, Object> request, boolean success, Object body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("type", "response");
        resp.put("request_seq", request.get("seq"));
        resp.put("success", success);
        resp.put("command", request.get("command"));
        if (body != null) resp.put("body", body);
        send(resp);
    }

    private void sendErrorResponse(Map<String, Object> request, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", Map.of("id", 1, "format", message));
        sendResponse(request, false, body);
    }

    private void sendEvent(String event, Object body) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "event");
        msg.put("event", event);
        if (body != null) msg.put("body", body);
        send(msg);
    }

    // ------------------------------------------------------------------
    // Request dispatch
    // ------------------------------------------------------------------

    private VirtualMachine vm;
    private Thread eventThread;

    @SuppressWarnings("unchecked")
    private void handleRequest(Map<String, Object> req) {
        String command = (String) req.get("command");
        Map<String, Object> args = (Map<String, Object>) req.getOrDefault("arguments", Map.of());
        try {
            switch (command) {
                case "initialize" -> onInitialize(req);
                case "attach" -> onAttach(req, args);
                case "setBreakpoints" -> onSetBreakpoints(req, args);
                case "configurationDone" -> onConfigurationDone(req);
                case "threads" -> onThreads(req);
                case "stackTrace" -> onStackTrace(req, args);
                case "scopes" -> onScopes(req, args);
                case "variables" -> onVariables(req, args);
                case "continue" -> onContinue(req);
                case "next" -> onStep(req, args, StepRequest.STEP_OVER);
                case "stepIn" -> onStep(req, args, StepRequest.STEP_INTO);
                case "stepOut" -> onStep(req, args, StepRequest.STEP_OUT);
                case "pause" -> onPause(req);
                case "disconnect" -> onDisconnect(req);
                default -> sendResponse(req, true, null);
            }
        } catch (Exception e) {
            sendErrorResponse(req, command + " failed: " + e);
        }
    }

    private void onInitialize(Map<String, Object> req) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("supportsConfigurationDoneRequest", true);
        sendResponse(req, true, caps);
        sendEvent("initialized", null);
    }

    // ------------------------------------------------------------------
    // Attach
    // ------------------------------------------------------------------

    private void onAttach(Map<String, Object> req, Map<String, Object> args) throws Exception {
        String host = String.valueOf(args.getOrDefault("hostName", "localhost"));
        int port = ((Number) args.getOrDefault("port", 5005)).intValue();

        AttachingConnector connector = null;
        for (Connector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (c.transport() != null && "dt_socket".equals(c.transport().name())) {
                connector = (AttachingConnector) c;
                break;
            }
        }
        if (connector == null) throw new IllegalStateException("no dt_socket attaching connector available");

        Map<String, Connector.Argument> cargs = connector.defaultArguments();
        cargs.get("hostname").setValue(host);
        cargs.get("port").setValue(String.valueOf(port));
        vm = connector.attach(cargs);

        // Flix's own generated classes (Def$foo, Clo$bar, ...) are unqualified (no package), so
        // excluding these known-irrelevant namespaces leaves essentially only user code visible.
        // Without this, watching every class the whole JVM loads (including Flix's own Scala
        // compiler/runtime) makes startup extremely slow under SUSPEND_ALL.
        EventRequestManager erm = vm.eventRequestManager();
        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        for (String pattern : new String[]{
                "java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "scala.*",
                "dev.flix.runtime.*", "ca.uwaterloo.*", "com.*", "org.*", "net.*"}) {
            cpr.addClassExclusionFilter(pattern);
        }
        cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        cpr.enable();

        eventThread = new Thread(this::eventLoop, "flix-debug-jdi-events");
        eventThread.setDaemon(true);
        eventThread.start();

        sendResponse(req, true, null);
    }

    private void onConfigurationDone(Map<String, Object> req) {
        sendResponse(req, true, null);
        // The target JVM is suspended at startup (JDWP suspend=y) so that
        // breakpoints set here bind before any user code runs. Resume it
        // now that configuration (breakpoints, etc.) is done.
        if (vm != null) vm.resume();
    }

    // ------------------------------------------------------------------
    // Breakpoints
    // ------------------------------------------------------------------

    /** A breakpoint requested for a source file, not yet necessarily resolved to a JDI location. */
    private static final class PendingBreakpoint {
        final int id;
        final int line;
        boolean verified;
        PendingBreakpoint(int id, int line) { this.id = id; this.line = line; }
    }

    /** file path (as given by the client) -> requested lines, replaced wholesale on each setBreakpoints call. */
    private final Map<String, List<PendingBreakpoint>> pendingByPath = new LinkedHashMap<>();
    /** file path -> the BreakpointRequests we created for it, so a later setBreakpoints call can clear them. */
    private final Map<String, List<BreakpointRequest>> requestsByPath = new LinkedHashMap<>();
    private int nextBreakpointId = 1;

    @SuppressWarnings("unchecked")
    private void onSetBreakpoints(Map<String, Object> req, Map<String, Object> args) {
        Map<String, Object> source = (Map<String, Object>) args.get("source");
        String path = String.valueOf(source.get("path"));
        List<Map<String, Object>> breakpoints = (List<Map<String, Object>>) args.getOrDefault("breakpoints", List.of());

        EventRequestManager erm = vm.eventRequestManager();
        for (BreakpointRequest r : requestsByPath.getOrDefault(path, List.of())) {
            erm.deleteEventRequest(r);
        }
        requestsByPath.put(path, new ArrayList<>());

        List<PendingBreakpoint> pending = new ArrayList<>();
        List<Object> resultBreakpoints = new ArrayList<>();
        for (Map<String, Object> bp : breakpoints) {
            int line = ((Number) bp.get("line")).intValue();
            pending.add(new PendingBreakpoint(nextBreakpointId++, line));
        }
        pendingByPath.put(path, pending);

        boolean[] resolved = new boolean[pending.size()];
        if (vm != null) {
            for (ReferenceType rt : vm.allClasses()) {
                resolveBreakpointsIn(rt, path, pending, resolved, false);
            }
        }
        for (int i = 0; i < pending.size(); i++) {
            pending.get(i).verified = resolved[i];
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", pending.get(i).id);
            b.put("verified", resolved[i]);
            b.put("line", pending.get(i).line);
            resultBreakpoints.add(b);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("breakpoints", resultBreakpoints);
        sendResponse(req, true, body);
    }

    /**
     * Tries to resolve as many of `pending`'s not-yet-resolved lines as possible against `rt`,
     * emitting a "breakpoint" (reason: "changed") event for any that newly verify -- e.g. when
     * called from a class-prepare handler after the initial setBreakpoints response already
     * reported them as unverified.
     */
    private void resolveBreakpointsIn(ReferenceType rt, String path, List<PendingBreakpoint> pending, boolean[] resolved, boolean emitEvents) {
        String baseName = basename(path);
        List<String> strata;
        try {
            strata = rt.availableStrata();
        } catch (Exception e) {
            return;
        }
        List<String> tryStrata = new ArrayList<>();
        if (strata.contains("Flix")) tryStrata.add("Flix");
        tryStrata.add(rt.defaultStratum());

        for (String stratum : tryStrata) {
            List<String> sourceNames;
            try {
                sourceNames = rt.sourceNames(stratum);
            } catch (AbsentInformationException e) {
                continue;
            }
            boolean matches = false;
            for (String name : sourceNames) {
                if (name.equals(baseName) || path.endsWith(name)) { matches = true; break; }
            }
            if (!matches) continue;

            for (int i = 0; i < pending.size(); i++) {
                if (resolved[i]) continue;
                int line = pending.get(i).line;
                List<Location> locs;
                try {
                    locs = rt.locationsOfLine(stratum, baseName, line);
                } catch (AbsentInformationException e) {
                    continue;
                }
                if (locs.isEmpty()) continue;
                EventRequestManager erm = vm.eventRequestManager();
                for (Location loc : locs) {
                    BreakpointRequest br = erm.createBreakpointRequest(loc);
                    br.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                    br.enable();
                    requestsByPath.computeIfAbsent(path, k -> new ArrayList<>()).add(br);
                    debug("breakpoint set at " + loc);
                }
                resolved[i] = true;
                if (emitEvents) {
                    PendingBreakpoint pb = pending.get(i);
                    pb.verified = true;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("id", pb.id);
                    b.put("verified", true);
                    b.put("line", pb.line);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("reason", "changed");
                    body.put("breakpoint", b);
                    sendEvent("breakpoint", body);
                }
            }
            return;
        }
    }

    private static String basename(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx < 0 ? path : path.substring(idx + 1);
    }

    // ------------------------------------------------------------------
    // Threads / stack / scopes / variables
    // ------------------------------------------------------------------

    private void onThreads(Map<String, Object> req) {
        List<Object> threads = new ArrayList<>();
        if (vm != null) {
            for (ThreadReference t : vm.allThreads()) {
                Map<String, Object> jt = new LinkedHashMap<>();
                jt.put("id", (int) t.uniqueID());
                jt.put("name", t.name());
                threads.add(jt);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threads", threads);
        sendResponse(req, true, body);
    }

    /** synthetic frameId -> (threadId, frame index), since JDI StackFrame objects go stale across resumes. */
    private final Map<Integer, int[]> frameById = new LinkedHashMap<>();
    private int nextFrameId = 1;

    private ThreadReference threadById(long id) {
        for (ThreadReference t : vm.allThreads()) {
            if (t.uniqueID() == id) return t;
        }
        return null;
    }

    private void onStackTrace(Map<String, Object> req, Map<String, Object> args) throws IncompatibleThreadStateException {
        long threadId = ((Number) args.get("threadId")).longValue();
        ThreadReference thread = threadById(threadId);
        List<Object> frames = new ArrayList<>();
        if (thread != null) {
            List<StackFrame> stack = thread.frames();
            for (int i = 0; i < stack.size(); i++) {
                StackFrame sf = stack.get(i);
                Location loc = sf.location();
                int frameId = nextFrameId++;
                frameById.put(frameId, new int[]{(int) threadId, i});

                String stratum = loc.declaringType().availableStrata().contains("Flix") ? "Flix" : loc.declaringType().defaultStratum();
                String sourcePath;
                int line;
                try {
                    sourcePath = loc.sourcePath(stratum);
                    line = loc.lineNumber(stratum);
                } catch (AbsentInformationException e) {
                    sourcePath = loc.declaringType().name();
                    line = loc.lineNumber();
                }

                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("id", frameId);
                frame.put("name", loc.method().name());
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("name", basename(sourcePath));
                source.put("path", sourcePath);
                frame.put("source", source);
                frame.put("line", line);
                frame.put("column", 1);
                frames.add(frame);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stackFrames", frames);
        body.put("totalFrames", frames.size());
        sendResponse(req, true, body);
    }

    private void onScopes(Map<String, Object> req, Map<String, Object> args) {
        int frameId = ((Number) args.get("frameId")).intValue();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("name", "Locals");
        scope.put("variablesReference", frameId);
        scope.put("expensive", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopes", List.of(scope));
        sendResponse(req, true, body);
    }

    private void onVariables(Map<String, Object> req, Map<String, Object> args) throws IncompatibleThreadStateException {
        int variablesReference = ((Number) args.get("variablesReference")).intValue();
        List<Object> vars = new ArrayList<>();
        int[] loc = frameById.get(variablesReference);
        if (loc != null) {
            ThreadReference thread = threadById(loc[0]);
            if (thread != null && loc[1] < thread.frameCount()) {
                StackFrame sf = thread.frame(loc[1]);
                try {
                    for (LocalVariable lv : sf.visibleVariables()) {
                        Value v = sf.getValue(lv);
                        Map<String, Object> var = new LinkedHashMap<>();
                        var.put("name", lv.name());
                        var.put("value", formatValue(v));
                        var.put("type", lv.typeName());
                        var.put("variablesReference", 0);
                        vars.add(var);
                    }
                } catch (AbsentInformationException e) {
                    // no local variable table for this frame; nothing to show.
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("variables", vars);
        sendResponse(req, true, body);
    }

    private static String formatValue(Value v) {
        if (v == null) return "null";
        if (v instanceof StringReference sr) return "\"" + sr.value() + "\"";
        if (v instanceof ObjectReference or) {
            return or.referenceType().name() + "@" + or.uniqueID();
        }
        return v.toString();
    }

    // ------------------------------------------------------------------
    // Execution control
    // ------------------------------------------------------------------

    private void onContinue(Map<String, Object> req) {
        frameById.clear();
        vm.resume();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allThreadsContinued", true);
        sendResponse(req, true, body);
    }

    private void onStep(Map<String, Object> req, Map<String, Object> args, int depth) {
        long threadId = ((Number) args.get("threadId")).longValue();
        ThreadReference thread = threadById(threadId);
        if (thread == null) { sendResponse(req, true, null); return; }
        EventRequestManager erm = vm.eventRequestManager();
        StepRequest step = erm.createStepRequest(thread, StepRequest.STEP_LINE, depth);
        step.addCountFilter(1);
        step.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        step.enable();
        frameById.clear();
        sendResponse(req, true, null);
        vm.resume();
    }

    private void onPause(Map<String, Object> req) {
        if (vm != null) vm.suspend();
        sendResponse(req, true, null);
        if (vm != null && !vm.allThreads().isEmpty()) {
            sendStoppedEvent(vm.allThreads().get(0), "pause");
        }
    }

    private void onDisconnect(Map<String, Object> req) {
        try {
            if (vm != null) vm.dispose();
        } catch (Exception ignored) {
        }
        sendResponse(req, true, null);
        System.exit(0);
    }

    private void sendStoppedEvent(ThreadReference thread, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("threadId", (int) thread.uniqueID());
        body.put("allThreadsStopped", true);
        sendEvent("stopped", body);
    }

    // ------------------------------------------------------------------
    // JDI event loop
    // ------------------------------------------------------------------

    private void eventLoop() {
        try {
            EventQueue queue = vm.eventQueue();
            while (true) {
                EventSet set = queue.remove();
                boolean keepRunning = true;
                for (Event e : set) {
                    try {
                        if (e instanceof ClassPrepareEvent cpe) {
                            onClassPrepare(cpe.referenceType());
                        } else if (e instanceof BreakpointEvent be) {
                            debug("BreakpointEvent at " + be.location());
                            sendStoppedEvent(be.thread(), "breakpoint");
                            keepRunning = false;
                        } else if (e instanceof StepEvent se) {
                            vm.eventRequestManager().deleteEventRequest(se.request());
                            sendStoppedEvent(se.thread(), "step");
                            keepRunning = false;
                        } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                            sendEvent("terminated", null);
                            return;
                        }
                    } catch (Throwable t) {
                        debug("error handling event " + e + ": " + t);
                        t.printStackTrace(System.err);
                    }
                }
                if (keepRunning) set.resume();
                // otherwise: leave the event set (and its threads) suspended until `continue`/step resumes them.
            }
        } catch (VMDisconnectedException e) {
            sendEvent("terminated", null);
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            debug("event loop died: " + t);
            t.printStackTrace(System.err);
        }
    }

    private static void debug(String msg) {
        System.err.println("[flix-debug-adapter] " + msg);
    }

    private void onClassPrepare(ReferenceType rt) {
        for (Map.Entry<String, List<PendingBreakpoint>> entry : pendingByPath.entrySet()) {
            String path = entry.getKey();
            List<PendingBreakpoint> pending = entry.getValue();
            boolean[] resolved = new boolean[pending.size()];
            // Re-check which lines already have a live BreakpointRequest so we don't duplicate them.
            List<BreakpointRequest> existing = requestsByPath.getOrDefault(path, List.of());
            for (int i = 0; i < pending.size(); i++) {
                int line = pending.get(i).line;
                for (BreakpointRequest r : existing) {
                    if (lineOf(r) == line) { resolved[i] = true; break; }
                }
            }
            resolveBreakpointsIn(rt, path, pending, resolved, true);
        }
    }

    private static int lineOf(BreakpointRequest r) {
        Location loc = r.location();
        try {
            return loc.lineNumber(loc.declaringType().defaultStratum());
        } catch (Exception e) {
            return loc.lineNumber();
        }
    }

    // ------------------------------------------------------------------
    // Minimal JSON (only what DAP messages need: objects, arrays, strings,
    // numbers, booleans, null).
    // ------------------------------------------------------------------

    static final class Json {
        static Object parse(String s) {
            return new Parser(s).parseValue();
        }

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            writeValue(o, sb);
            return sb.toString();
        }

        private static void writeValue(Object o, StringBuilder sb) {
            if (o == null) {
                sb.append("null");
            } else if (o instanceof String s) {
                writeString(s, sb);
            } else if (o instanceof Boolean b) {
                sb.append(b.toString());
            } else if (o instanceof Number n) {
                if (n instanceof Double || n instanceof Float) sb.append(n.toString());
                else sb.append(n.longValue());
            } else if (o instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    writeValue(e.getValue(), sb);
                }
                sb.append('}');
            } else if (o instanceof List<?> l) {
                sb.append('[');
                boolean first = true;
                for (Object v : l) {
                    if (!first) sb.append(',');
                    first = false;
                    writeValue(v, sb);
                }
                sb.append(']');
            } else {
                writeString(o.toString(), sb);
            }
        }

        private static void writeString(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }

        private static final class Parser {
            private final String s;
            private int i = 0;

            Parser(String s) { this.s = s; }

            Object parseValue() {
                skipWs();
                char c = s.charAt(i);
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't' -> { i += 4; yield Boolean.TRUE; }
                    case 'f' -> { i += 5; yield Boolean.FALSE; }
                    case 'n' -> { i += 4; yield null; }
                    default -> parseNumber();
                };
            }

            private Map<String, Object> parseObject() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; // {
                skipWs();
                if (peek() == '}') { i++; return m; }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    i++; // :
                    Object value = parseValue();
                    m.put(key, value);
                    skipWs();
                    if (peek() == ',') { i++; continue; }
                    if (peek() == '}') { i++; break; }
                    throw new IllegalArgumentException("malformed object at " + i);
                }
                return m;
            }

            private List<Object> parseArray() {
                List<Object> l = new ArrayList<>();
                i++; // [
                skipWs();
                if (peek() == ']') { i++; return l; }
                while (true) {
                    l.add(parseValue());
                    skipWs();
                    if (peek() == ',') { i++; continue; }
                    if (peek() == ']') { i++; break; }
                    throw new IllegalArgumentException("malformed array at " + i);
                }
                return l;
            }

            private String parseString() {
                i++; // opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default -> sb.append(e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            private Object parseNumber() {
                int start = i;
                while (i < s.length() && "-+.0123456789eE".indexOf(s.charAt(i)) >= 0) i++;
                String num = s.substring(start, i);
                if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
                return Long.parseLong(num);
            }

            private char peek() { return s.charAt(i); }

            private void skipWs() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            }
        }
    }
}
