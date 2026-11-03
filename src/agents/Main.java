package agents;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        int tourists = 3;
        boolean isMainContainer = true;
        String mainHost = "localhost";

        if (args.length >= 1) {
            try { tourists = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("client")) {
            isMainContainer = false;
        }
        if (args.length >= 3) mainHost = args[2];

        if (tourists < 1) {
            System.err.println("Количество туристов должно быть больше нуля.");
            return;
        }

        String prefix = getComputerPrefix();
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);

        StringBuilder agents = new StringBuilder();
        if (isMainContainer) {
            agents.append("manager:agents.ManagerAgent(").append(tourists).append(")");

            String[] names = {"палатка", "спальник", "котелок", "тушенка",
                    "фонарь", "аптечка", "веревка", "кружка",
                    "ложка", "горелка", "зажигалка", "соль"};
            double[] weights = {3.5, 3.1, 2.7, 2.4, 2.0, 1.9,
                    1.7, 1.5, 1.3, 1.1, 0.6, 0.4};

            for (int i = 0; i < names.length; i++) {
                agents.append(";item_").append(names[i])
                        .append(":agents.ItemAgent(").append(weights[i]).append(")");
            }
        } else {
            for (int i = 1; i <= tourists; i++) {
                if (agents.length() > 0) agents.append(";");
                agents.append(prefix).append("_tourist_").append(suffix)
                        .append("_").append(i).append(":agents.TouristAgent");
            }
        }

        List<String> jadeArgs = new ArrayList<>();
        if (isMainContainer) {
            jadeArgs.add("-gui");
        } else {
            jadeArgs.add("-container");
            jadeArgs.add("-container-name");
            jadeArgs.add("Client_" + suffix);
            jadeArgs.add("-host");
            jadeArgs.add(mainHost);
        }
        jadeArgs.add("-agents");
        jadeArgs.add(agents.toString());

        System.out.println("Компьютер: " + prefix);
        System.out.println("Режим: " + (isMainContainer ? "СЕРВЕР" : "КЛИЕНТ"));
        System.out.println("Запускаемые агенты: " + agents);
        jade.Boot.main(jadeArgs.toArray(new String[0]));
    }

    private static String getComputerPrefix() {
        try {
            return InetAddress.getLocalHost().getHostName()
                    .replaceAll("[^a-zA-Z0-9]", "");
        } catch (Exception e) {
            return "PC";
        }
    }
}
