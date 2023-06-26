package dinos;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ServerCoC {
    private HttpServer server;
    private Executor executorService = Executors.newSingleThreadExecutor();

    public ServerCoC()
    {
        try
        {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 4000), 0);
        } catch (IOException e)
        {
            System.err.println("Unable to create server. Reason: " + e.getMessage());
            System.exit(1);
        }
    }

    public void start()
    {
        server.createContext("/", new RequestHandler());
        server.setExecutor(this.executorService);
        server.start();
        System.out.println(String.format("Server created under %s", this.server.getAddress().toString()));
    }

    class RequestHandler implements HttpHandler
    {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException
        {
            System.out.println(String.format("incoming request:\n\tURI: %s\n\tMETHOD: %s",
                    httpExchange.getRequestURI(),
                    httpExchange.getRequestMethod()));

            ValidEndpoint requestedEndpoint = ValidEndpoint.getEndpointFromURI(httpExchange.getRequestURI().toString());
            // check for valid endpoint
            if (requestedEndpoint == null)
            {
                respondFailure(httpExchange, 404);
            }
            // check if method type matches
            else if (!requestedEndpoint.requestMethod.equals(httpExchange.getRequestMethod()))
            {
                respondFailure(httpExchange, 405);
            }
            else
            {
                // TODO: implement proper responses
                respondFailure(httpExchange, 200);
            }
        }

        private void respondFailure(HttpExchange exchange, int code)
        {
            // if the tcp connection somehow remains open after all this then I cannot be bothered.
            OutputStream stream = exchange.getResponseBody();
            exchange.getResponseHeaders().set("Connection", "close");
            try {
                exchange.sendResponseHeaders(code, 0);
                stream.flush();
                stream.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                exchange.close();
            }
        }
    }

    public static void main(String[] args) {
        ServerCoC s = new ServerCoC();
        s.start();
    }
}

enum ValidEndpoint
{
    EXIFTOOL("/exiftool", "POST"),
    MMB("/mmb", "POST"),
    WIRESHARK("/wireshark", "POST"),
    GET("/", "GET");

    final String uri;
    final String requestMethod;
    private ValidEndpoint(String uri, String requestMethod)
    {
        this.uri = uri;
        this.requestMethod = requestMethod;
    }

    static ValidEndpoint getEndpointFromURI(String requestedUri)
    {
        return Arrays.stream(ValidEndpoint.values()).filter(ve -> ve.uri.equals(requestedUri)).findAny().orElse(null);
    }
}