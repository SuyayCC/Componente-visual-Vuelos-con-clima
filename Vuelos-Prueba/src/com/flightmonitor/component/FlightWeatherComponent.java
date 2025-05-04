package com.flightmonitor.component;


import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class FlightWeatherComponent extends JPanel {

    private final String AVIATIONSTACK_API_KEY = "20540ede82403157e0be8f2a96d4fc23";
    private final String OPENWEATHER_API_KEY = "c12c75ae491c7fade9a5ebf87112677b";

    private JTextField txtFlightNumber, txtOrigin, txtDestination;
    private JTextArea txtResults;
    private JButton btnSearch;
    private JProgressBar progressBar;
    private String lastApiResponse = "";

    public FlightWeatherComponent() {
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(650, 500));

        JPanel inputPanel = new JPanel(new GridLayout(4, 2, 5, 5));
        inputPanel.add(new JLabel("Número de Vuelo (ej: IB1234):"));
        txtFlightNumber = new JTextField("IB1234");
        inputPanel.add(txtFlightNumber);
        inputPanel.add(new JLabel("Origen (código IATA ej: MAD):"));
        txtOrigin = new JTextField("MAD");
        inputPanel.add(txtOrigin);
        inputPanel.add(new JLabel("Destino (código IATA ej: BCN):"));
        txtDestination = new JTextField("BCN");
        inputPanel.add(txtDestination);

        btnSearch = new JButton("Buscar Vuelo y Clima");
        btnSearch.addActionListener(this::searchFlight);
        inputPanel.add(new JLabel());
        inputPanel.add(btnSearch);

        add(inputPanel, BorderLayout.NORTH);

        txtResults = new JTextArea();
        txtResults.setEditable(false);
        txtResults.setFont(new Font("Monospaced", Font.PLAIN, 12));
        add(new JScrollPane(txtResults), BorderLayout.CENTER);

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        add(progressBar, BorderLayout.SOUTH);
    }

    private void searchFlight(ActionEvent e) {
        String flightNumber = txtFlightNumber.getText().trim();
        String origin = txtOrigin.getText().trim().toUpperCase();
        String destination = txtDestination.getText().trim().toUpperCase();

        if (flightNumber.isEmpty() || origin.isEmpty() || destination.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Por favor complete todos los campos", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        btnSearch.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setString("Iniciando búsqueda...");

        new Thread(() -> {
            try {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(25);
                    progressBar.setString("Consultando información del vuelo...");
                });

                String flightInfo = getFlightInfo(flightNumber, origin, destination);

                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(50);
                    progressBar.setString("Consultando clima en origen...");
                });

                String originWeather = getWeatherInfo(origin);

                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(75);
                    progressBar.setString("Consultando clima en destino...");
                });

                String destinationWeather = getWeatherInfo(destination);

                SwingUtilities.invokeLater(() -> {
                    txtResults.setText(flightInfo + "\n\n=== CLIMA EN ORIGEN ===\n" + originWeather +
                            "\n\n=== CLIMA EN DESTINO ===\n" + destinationWeather);
                    progressBar.setValue(100);
                    progressBar.setString("Búsqueda completada");
                    btnSearch.setEnabled(true);
                });

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    txtResults.setText("Error: " + ex.getMessage() +
                            "\n\nPosibles causas:" +
                            "\n1. Vuelo no encontrado" +
                            "\n2. Códigos IATA incorrectos" +
                            "\n3. Problema con la API" +
                            "\n\nRespuesta API: " + getLastApiResponse());
                    progressBar.setString("Error en la búsqueda");
                    btnSearch.setEnabled(true);
                });
            }
        }).start();
    }

    private String getFlightInfo(String flightIata, String depIata, String arrIata) throws Exception {
        String url = "http://api.aviationstack.com/v1/flights?" +
                "access_key=" + AVIATIONSTACK_API_KEY +
                "&flight_iata=" + flightIata +
                "&dep_iata=" + depIata +
                "&arr_iata=" + arrIata;

        String json = makeApiCall(url);
        lastApiResponse = json;

        if (!json.contains("\"data\"")) {
            throw new Exception("Formato de respuesta inesperado");
        }

        if (json.contains("\"data\":[]")) {
            throw new Exception("No se encontraron datos para el vuelo " + flightIata);
        }

        String airline = extractJsonValue(json, "\"name\":");
        String status = extractJsonValue(json, "\"flight_status\":");

        String departureInfo = extractJsonObject(json, "\"departure\":");
        String depTime = cleanJsonValue(extractJsonValue(departureInfo, "\"estimated\":"));
        if (depTime == null || depTime.isEmpty()) {
            depTime = cleanJsonValue(extractJsonValue(departureInfo, "\"scheduled\":"));
        }

        String depTerminal = extractJsonValue(departureInfo, "\"terminal\":");

        String arrivalInfo = extractJsonObject(json, "\"arrival\":");
        String arrTime = cleanJsonValue(extractJsonValue(arrivalInfo, "\"estimated\":"));
        if (arrTime == null || arrTime.isEmpty()) {
            arrTime = cleanJsonValue(extractJsonValue(arrivalInfo, "\"scheduled\":"));
        }

        String arrTerminal = extractJsonValue(arrivalInfo, "\"terminal\":");

        if (depTime == null || arrTime == null) {
            throw new Exception("No se pudo obtener la información de horarios del vuelo");
        }

        Date departure = parseAviationStackDate(depTime);
        Date arrival = parseAviationStackDate(arrTime);

        SimpleDateFormat displayFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");
        long duration = arrival.getTime() - departure.getTime();

        return "✈️ VUELO " + flightIata + " (" + airline + ")\n" +
                "--------------------------------\n" +
                "• Estado: " + status + "\n" +
                "• Salida: " + displayFormat.format(departure) +
                (depTerminal.equals("N/A") ? "" : " (Terminal " + depTerminal + ")") + "\n" +
                "• Llegada: " + displayFormat.format(arrival) +
                (arrTerminal.equals("N/A") ? "" : " (Terminal " + arrTerminal + ")") + "\n" +
                "• Duración: " + (duration / 3600000) + "h " + ((duration % 3600000) / 60000) + "m";
    }

    private String extractJsonValue(String json, String key) {
        try {
            int startIdx = json.indexOf(key);
            if (startIdx == -1) return null;

            startIdx += key.length();

            while (startIdx < json.length() && (json.charAt(startIdx) == ':' || Character.isWhitespace(json.charAt(startIdx)))) {
                startIdx++;
            }

            boolean isString = json.charAt(startIdx) == '"';
            if (isString) startIdx++;

            int endIdx = startIdx;
            while (endIdx < json.length()) {
                char c = json.charAt(endIdx);
                if (isString) {
                    if (c == '"' && json.charAt(endIdx - 1) != '\\') break;
                } else {
                    if (c == ',' || c == '}' || c == ']') break;
                }
                endIdx++;
            }

            return json.substring(startIdx, endIdx).trim();

        } catch (Exception e) {
            return null;
        }
    }

    private String cleanJsonValue(String value) {
        if (value == null) return null;
        value = value.replace("\"", "").trim();
        if (value.equalsIgnoreCase("null") || value.equals("N/A")) return null;
        return value;
    }

    private Date parseAviationStackDate(String dateStr) throws Exception {
        if (dateStr == null || dateStr.isEmpty() || dateStr.equals("\"\"") || dateStr.equals("N/A")) {
            throw new Exception("Fecha vacía o inválida recibida");
        }

        String[] formats = {
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        };

        for (String format : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf.parse(dateStr);
            } catch (Exception e) {}
        }

        throw new Exception("No se pudo interpretar el formato de fecha: " + dateStr);
    }

    private String extractJsonObject(String json, String key) {
        int startIdx = json.indexOf(key);
        if (startIdx == -1) return "";
        startIdx = json.indexOf('{', startIdx);
        int braceCount = 1, i = startIdx + 1;
        while (braceCount > 0 && i < json.length()) {
            char c = json.charAt(i);
            if (c == '{') braceCount++;
            else if (c == '}') braceCount--;
            i++;
        }
        return json.substring(startIdx, i);
    }

    private String getWeatherInfo(String iataCode) throws Exception {
        String city = getCityFromIata(iataCode);
        String url = "http://api.openweathermap.org/data/2.5/weather?q=" + city +
            "&units=metric&lang=es&APPID=" + OPENWEATHER_API_KEY;

        String json = makeApiCall(url);
        lastApiResponse = json;

        String description = extractJsonValue(json, "\"description\":");
        String temp = extractJsonValue(json, "\"temp\":");
        String humidity = extractJsonValue(json, "\"humidity\":");
        String wind = extractJsonValue(json, "\"speed\":");

        return "🌤️ " + city.toUpperCase() + "\n" +
            "--------------------------------\n" +
            "• Condición: " + description + "\n" +
            "• Temperatura: " + temp + "°C\n" +
            "• Humedad: " + humidity + "%\n" +
            "• Viento: " + wind + " km/h";
    }

    private String getCityFromIata(String iata) {
        switch (iata.toUpperCase()) {
            case "MAD": return "Madrid";
            case "BCN": return "Barcelona";
            case "JFK": return "New York";
            case "LAX": return "Los Angeles";
            case "LAS": return "Las Vegas";
            case "MEX": return "Mexico City";
            case "ORD": return "Chicago";
            case "DFW": return "Dallas";
            case "ATL": return "Atlanta";
            case "DEN": return "Denver";
            case "PEK": return "Beijing";
            case "AKL": return "Auckland";
            default: return iata;
        }
    }

    private String makeApiCall(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        if (conn.getResponseCode() != 200) {
            throw new Exception("Error en la conexión (Código: " + conn.getResponseCode() + ")");
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private String getLastApiResponse() {
        if (lastApiResponse.length() > 300) {
            return lastApiResponse.substring(0, 300) + "...";
        }
        return lastApiResponse.isEmpty() ? "No hay respuesta registrada" : lastApiResponse;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Monitor de Vuelos y Clima");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().add(new FlightWeatherComponent());
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
