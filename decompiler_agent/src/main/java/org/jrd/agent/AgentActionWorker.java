package org.jrd.agent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This class handles the socket accepting and request processing from the
 * decompiler
 *
 * @author pmikova
 */
public class AgentActionWorker extends Thread {

    private InstrumentationProvider provider;
    private Boolean abort = false;

    private static final String AGENT_ERROR_ID = "ERROR";

    private static String toError(String message) {
        return AGENT_ERROR_ID + " " + message;
    }

    private static String toError(Exception ex) {
        return toError(ex.toString());
    }

    public AgentActionWorker(Socket socket, InstrumentationProvider provider) {
        this.provider = provider;

        try {
            executeRequest(socket);
        } catch (Exception e) {
            AgentLogger.getLogger().log(new RuntimeException("Error when trying to execute the request. Cause: ", e));
            try {
                socket.close();
            } catch (IOException e1) {
                AgentLogger.getLogger().log(new RuntimeException("Error when trying to close the socket. Cause: ", e1));
            }
        }
    }

    private void executeRequest(Socket socket) {
        InputStream is = null;
        try {
            is = socket.getInputStream();
        } catch (IOException e) {
            AgentLogger.getLogger().log(new RuntimeException("Error when opening the socket input stream. Cause: ", e));
            try {
                socket.close();
            } catch (IOException e1) {
                AgentLogger.getLogger().log(new RuntimeException("Error when closing the socket. Cause: ", e1));
            }
            return;
        }

        OutputStream os = null;
        try {
            os = socket.getOutputStream();
        } catch (IOException e) {
            AgentLogger.getLogger().log(new RuntimeException("Error when opening the socket output stream. Cause: ", e));
            try {
                socket.close();
            } catch (IOException e1) {
                AgentLogger.getLogger().log(new RuntimeException("Error when closing the socket. Cause: ", e1));
            }
            return;
        }
        BufferedReader inputStream = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        BufferedWriter outputStream = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));

        String line = null;
        try {
            line = inputStream.readLine();
        } catch (IOException e) {
            AgentLogger.getLogger().log(new RuntimeException("Exception occurred during reading of the line: ", e));
        }
        try {
            if (null == line) {
                outputStream.write(toError("null welcome line") + "\n");
                outputStream.flush();
            } else {
                switch (line) {
                    case "HALT":
                        closeSocket(outputStream, socket);
                        AgentLogger.getLogger().log("Agent received HALT command, closing socket and exiting.");
                        break;
                    case "CLASSES":
                        getAllLoadedClasses(outputStream);
                        break;
                    case "BYTES":
                        sendByteCode(inputStream, outputStream);
                        break;
                    case "OVERWRITE":
                        receiveByteCode(inputStream, outputStream);
                        break;
                    case "INIT_CLASS":
                        initClass(inputStream, outputStream);
                        break;
                    default:
                        outputStream.write(toError("unknown command " + line) + "\n");
                        outputStream.flush();
                        break;
                }
            }
        } catch (IOException e) {
            AgentLogger.getLogger().log(new RuntimeException("Error when trying to process the request:", e));
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                AgentLogger.getLogger().log(new RuntimeException("Error when trying to close the socket:", e));
            }
        }
    }

    private void getAllLoadedClasses(BufferedWriter out) throws IOException {
        out.write("CLASSES");
        out.newLine();
        LinkedBlockingQueue<String> classNames = new LinkedBlockingQueue<String>(1024);
        new Thread(() -> {
            try {
                provider.getClassesNames(classNames, abort);
            } catch (InterruptedException e) {
                AgentLogger.getLogger().log(e);
            }
        }).start();
        while (true) {
            String x = classNames.poll();
            if (x == null) {
                continue;
            }
            if ("---END---".equals(x)) {
                break;
            } else {
                out.write(x);
                out.newLine();
            }
        }
        out.flush();
    }

    private void sendByteCode(BufferedReader in, BufferedWriter out) throws IOException {
        String className = in.readLine();
        if (className == null) {
            out.write(toError("no class name provided for bytecode") + "\n");
            out.flush();
            return;
        }
        try {
            byte[] body = provider.findClassBody(className);
            String encoded = Base64.getEncoder().encodeToString(body);
            out.write("BYTES");
            out.newLine();
            out.write(encoded);
            out.newLine();
        } catch (Exception ex) {
            AgentLogger.getLogger().log(ex);
            out.write(toError(ex) + "\n");
        }
        out.flush();
    }

    private void initClass(BufferedReader in, BufferedWriter out) throws IOException {
        String fqn = in.readLine();
        if (fqn == null) {
            out.write(toError("no fqn") + "\n");
            out.flush();
            return;
        }
        try {
            Class.forName(fqn);
            out.write("DONE");
            out.newLine();
        } catch (Exception ex) {
            AgentLogger.getLogger().log(ex);
            out.write(toError(ex) + "\n");
        }
        out.flush();
    }

    private void receiveByteCode(BufferedReader in, BufferedWriter out) throws IOException {
        String className = in.readLine();
        if (className == null) {
            out.write(toError("no classname to upload") + "\n");
            out.flush();
            return;
        }
        String classBodyBase64 = in.readLine();
        if (classBodyBase64 == null) {
            out.write(toError("no class body") + "\n");
            out.flush();
            return;
        }
        try {
            provider.setClassBody(className, Base64.getDecoder().decode(classBodyBase64));
            out.write("DONE"); // overwrite specific done?
            out.newLine();
        } catch (Exception ex) {
            AgentLogger.getLogger().log(ex);
            out.write(toError(ex) + "\n");
        }
        out.flush();
    }

    private void closeSocket(BufferedWriter out, Socket socket) throws IOException {
        out.write("GOODBYE");
        out.flush();
        socket.close();
        ConnectionDelegator.gracefulShutdown();
    }
}
