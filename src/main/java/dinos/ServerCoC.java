package dinos;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dinos.storagetypes.*;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServerCoC {
    private HttpServer server;
    private String fileStorage;
    private Executor executorService = Executors.newSingleThreadExecutor();

    public ServerCoC(String fileStorage)
    {
        this.fileStorage = fileStorage;
        try
        {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 4000), 0);
            this.prepareFileStorage();
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

    private void prepareFileStorage() throws IOException
    {
        Files.createDirectories(Paths.get(this.fileStorage));
    }

    class RequestHandler implements HttpHandler
    {
        private final Map<ValidEndpoint, Consumer<HttpExchange>> requestHandlers = Map.ofEntries(
                new AbstractMap.SimpleEntry<>(ValidEndpoint.GET, this::handleGET),
                new AbstractMap.SimpleEntry<>(ValidEndpoint.EXIFTOOL, this::handleEXIFTOOL),
                new AbstractMap.SimpleEntry<>(ValidEndpoint.MMB, this::handleMMB),
                new AbstractMap.SimpleEntry<>(ValidEndpoint.WIRESHARK, this::handleWIRESHARK)
        );

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
                handleFailure(httpExchange, 404);
            }
            // check if method type matches
            else if (!requestedEndpoint.requestMethod.equals(httpExchange.getRequestMethod()))
            {
                handleFailure(httpExchange, 405);
            }
            else
            {
                Consumer<HttpExchange> requestHandler = requestHandlers.getOrDefault(requestedEndpoint, null);
                if (requestHandler != null)
                {
                    requestHandler.accept(httpExchange);
                }
                else {
                    handleFailure(httpExchange, 500);
                }
            }
        }

        private void handleFailure(HttpExchange exchange, int code)
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

        private void handleGET(HttpExchange exchange)
        {
            List<ObservationReport> storedContent = new ArrayList<>();
            try {
                storedContent = readDataStore();
            } catch (IOException e) {
                System.err.println("IOException occured: " + e.getMessage());
            }

            ReportBundle bundle = new ReportBundle();
            bundle.getReports().addAll(storedContent);
            JAXBElement<ReportBundle> serializedBundle = new ObjectFactory().createReportBundle(bundle);

            try {
                JAXBContext context = JAXBContext.newInstance(ObjectFactory.class);
                Marshaller m = context.createMarshaller();
                StringWriter sw = new StringWriter();
                m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
                m.marshal(serializedBundle, sw);
                this.send(exchange, sw.toString());
            } catch (JAXBException e) {
                this.send(exchange, "");
                e.printStackTrace();
            }
        }

        private void handleEXIFTOOL(HttpExchange exchange)
        {
            String content = readRequestBody(exchange);
            ObservationReport report = new ObservationReport();
            report.setObservationData(content);
            GregorianCalendar c = new GregorianCalendar();
            c.setTime(Date.from(Instant.now()));
            try {
                report.setStorageTimestamp(DatatypeFactory.newInstance().newXMLGregorianCalendar(c));
            } catch (DatatypeConfigurationException e) {
                System.err.println("Configuration Exception: " + e.getMessage());
            }
            report.setDataStream(DataStream.STORAGE);
            report.setDataType(DataType.METADATA);
            report.setTool(Tool.EXIFTOOL);

            try {
                writeToFile(report);
            } catch (IOException e)
            {
                System.err.println("Unable to store submitted exiftool data. Reason: " + e.getMessage());
            }
            this.send(exchange, "");
        }

        private void handleMMB(HttpExchange exchange)
        {
            String content = readRequestBody(exchange);

            ObservationReport report = new ObjectFactory().createObservationReport();
            report.setObservationData(content);
            GregorianCalendar c = new GregorianCalendar();
            c.setTime(Date.from(Instant.now()));
            try {
                report.setStorageTimestamp(DatatypeFactory.newInstance().newXMLGregorianCalendar(c));
            } catch (DatatypeConfigurationException e) {
                System.err.println("Configuration Exception: " + e.getMessage());
            }
            report.setDataStream(DataStream.STORAGE);
            report.setDataType(DataType.METADATA);
            report.setTool(Tool.MMB);


            try {
                writeToFile(report);
            } catch (IOException e)
            {
                System.err.println("Unable to store submitted mmb data. Reason: " + e.getMessage());
            }
            this.send(exchange, "");
        }

        private void handleWIRESHARK(HttpExchange exchange)
        {
            String content = readRequestBody(exchange);
            ObservationReport report = new ObservationReport();
            report.setObservationData(content);
            GregorianCalendar c = new GregorianCalendar();
            c.setTime(Date.from(Instant.now()));
            try {
                report.setStorageTimestamp(DatatypeFactory.newInstance().newXMLGregorianCalendar(c));
            } catch (DatatypeConfigurationException e) {
                System.err.println("Configuration Exception: " + e.getMessage());
            }
            report.setDataStream(DataStream.NETWORK);
            report.setDataType(DataType.COMMUNICATION);
            report.setTool(Tool.WIRESHARK);

            try {
                writeToFile(report);
            } catch (IOException e)
            {
                System.err.println("Unable to store submitted wireshark data. Reason: " + e.getMessage());
            }
            this.send(exchange, "");
        }

        private String readRequestBody(HttpExchange http)
        {
            return new BufferedReader(new InputStreamReader(http.getRequestBody())).lines().collect(Collectors.joining("\n"));
        }

        private void send(HttpExchange http, String body)
        {
            OutputStream stream = http.getResponseBody();
            http.getResponseHeaders().set("Connection", "close");
            try {
                http.sendResponseHeaders(200, body.getBytes().length);
                stream.write(body.getBytes());
                stream.flush();
                stream.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                http.close();
            }
        }

        private void writeToFile(ObservationReport report) throws IOException {
            String fileName = Date.from(Instant.now()).toString().replaceAll("[ .,;:/-]", "").concat(".xml");

            try {
                JAXBContext context = JAXBContext.newInstance(ObservationReport.class);
                Marshaller m = context.createMarshaller();
                m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

                JAXBElement<ObservationReport> root = new ObjectFactory().createObservationReport(report);
                FileOutputStream os  = new FileOutputStream(ServerCoC.this.fileStorage + fileName);
                m.marshal(root, os);

            } catch (JAXBException e) {
                System.err.println("JAXB Exception occured!");
                e.printStackTrace();
            }
            System.out.println("New observation oeport stored under " + ServerCoC.this.fileStorage + fileName);
        }

        private List<ObservationReport> readDataStore() throws IOException {
            List<ObservationReport> storageContent = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(Paths.get(ServerCoC.this.fileStorage))) {
                paths.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        final JAXBContext context = JAXBContext.newInstance(ObjectFactory.class);
                        storageContent.add(((JAXBElement<ObservationReport>) context
                                .createUnmarshaller()
                                .unmarshal(new FileReader(file.toFile()))).getValue());
                    } catch (FileNotFoundException | JAXBException e) {
                        e.printStackTrace();
                    }
                });
            }
            return storageContent;
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

    public static void main(String[] args) {
        if (args.length < 1)
        {
            System.err.println("Provide a path for the server to store data at.");
            System.exit(1);
        }

        try {
            Paths.get(args[0]);
        } catch (InvalidPathException | NullPointerException e)
        {
            System.err.println("The path " + args[0] + " is invalid.");
        }

        ServerCoC s = new ServerCoC(args[0]);
        s.start();
    }
}