package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ManagerAgent extends Agent {
    private int expectedTourists = 3;

    private final Map<String, Double> itemWeights = new LinkedHashMap<>();
    private final Map<String, String> itemAgentNames = new HashMap<>();
    private final Map<String, List<String>> distribution = new LinkedHashMap<>();
    private final Map<String, AID> tourists = new LinkedHashMap<>();
    private final Set<String> readyItems = new HashSet<>();
    private final Set<String> noImprovement = new HashSet<>();

    private boolean started;
    private boolean stopped;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            try { expectedTourists = Integer.parseInt(args[0].toString()); }
            catch (NumberFormatException ignored) {}
        }

        registerInDF();
        loadItemsFromFile();

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }
                handleMessage(msg);
            }
        });

        System.out.println("[MANAGER] запущен. Ожидается туристов: " + expectedTourists);
    }

    private void handleMessage(ACLMessage msg) {
        String content = msg.getContent() == null ? "" : msg.getContent();
        String sender = msg.getSender().getLocalName();

        if (content.startsWith("READY_ITEM|")) {
            String[] p = content.split("\\|", 3);
            if (p.length == 3) {
                try {
                    itemWeights.put(p[1], Double.parseDouble(p[2].replace(',', '.')));
                    itemAgentNames.put(p[1], "item_" + p[1]);
                    readyItems.add(p[1]);
                } catch (NumberFormatException ignored) {}
            }
            tryStart();
        } else if (content.startsWith("READY_TOURIST|") || content.startsWith("REGISTER|")) {
            tourists.putIfAbsent(sender, msg.getSender());
            distribution.putIfAbsent(sender, new ArrayList<>());
            tryStart();
        } else if (content.startsWith("COMMIT_SWAP|")) {
            handleCommitSwap(content);
        } else if (content.startsWith("NO_IMPROVEMENT|")) {
            noImprovement.add(sender);
            if (started && noImprovement.containsAll(tourists.keySet())) {
                stopSystem("не осталось согласованных улучшающих обменов");
            }
        } else if (content.startsWith("REQUEST_STATE")) {
            sendStateTo(msg.getSender());
        }
    }

    private void tryStart() {
        if (started || stopped) return;
        if (tourists.size() < expectedTourists || itemWeights.isEmpty()
                || readyItems.size() < itemWeights.size()) return;

        started = true;
        createInitialDistribution();
        updateItemOwners();
        broadcastBags();
        System.out.println("[MANAGER] Начальное допустимое распределение создано.");
    }

    // Жадный алгоритм используется только для построения начального допустимого плана.
    private void createInitialDistribution() {
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(itemWeights.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        for (String tourist : tourists.keySet()) distribution.put(tourist, new ArrayList<>());

        for (Map.Entry<String, Double> item : sorted) {
            String lightest = null;
            double minWeight = Double.MAX_VALUE;
            for (String tourist : distribution.keySet()) {
                double w = bagWeight(distribution.get(tourist));
                if (w < minWeight) {
                    minWeight = w;
                    lightest = tourist;
                }
            }
            if (lightest != null) distribution.get(lightest).add(item.getKey());
        }
    }

    private void handleCommitSwap(String content) {
        // COMMIT_SWAP|initiator|partner|giveItem|takeItem
        String[] p = content.split("\\|", 5);
        if (p.length != 5 || stopped) return;

        String initiator = p[1];
        String partner = p[2];
        String give = p[3];
        String take = p[4];

        List<String> a = distribution.get(initiator);
        List<String> b = distribution.get(partner);

        if (a == null || b == null || !a.contains(give) || !b.contains(take)) {
            sendTo(tourists.get(initiator), "SWAP_REJECTED|устаревшее распределение");
            sendTo(tourists.get(partner), "SWAP_REJECTED|устаревшее распределение");
            return;
        }

        double oldDiff = calcDiff();
        a.remove(give);
        b.remove(take);
        a.add(take);
        b.add(give);

        double newDiff = calcDiff();
        // Обмен принимается только при строгом уменьшении общего разброса.
        if (newDiff + 1e-9 >= oldDiff) {
            a.remove(take);
            b.remove(give);
            a.add(give);
            b.add(take);
            sendTo(tourists.get(initiator), "SWAP_REJECTED|общий разброс не уменьшился");
            sendTo(tourists.get(partner), "SWAP_REJECTED|общий разброс не уменьшился");
            return;
        }

        noImprovement.clear();
        updateItemOwners();
        broadcastBags();
        System.out.printf("[MANAGER] Обмен подтверждён: %s ↔ %s; разброс %.2f -> %.2f кг%n",
                give, take, oldDiff, newDiff);
    }

    private void updateItemOwners() {
        for (Map.Entry<String, List<String>> e : distribution.entrySet()) {
            for (String item : e.getValue()) {
                AID itemAgent = new AID(itemAgentNames.get(item), AID.ISLOCALNAME);
                ACLMessage m = new ACLMessage(ACLMessage.INFORM);
                m.addReceiver(itemAgent);
                m.setContent("SET_OWNER|" + e.getKey());
                send(m);
            }
        }
    }

    private void broadcastBags() {
        double avg = calcAvg();
        double diff = calcDiff();
        for (String tourist : tourists.keySet()) {
            List<String> bag = distribution.get(tourist);
            sendTo(tourists.get(tourist),
                    "BAG_UPDATE|" + serialize(bag) + "|" + avg + "|" + diff + "|" + serializeWeights());
        }
    }

    private void sendStateTo(AID receiver) {
        ACLMessage m = new ACLMessage(ACLMessage.INFORM);
        m.addReceiver(receiver);
        m.setContent("STATE_UPDATE|" + calcAvg() + "|" + calcDiff());
        send(m);
    }

    private void sendTo(AID receiver, String content) {
        if (receiver == null) return;
        ACLMessage m = new ACLMessage(ACLMessage.INFORM);
        m.addReceiver(receiver);
        m.setContent(content);
        send(m);
    }

    private void stopSystem(String reason) {
        if (stopped) return;
        stopped = true;
        for (AID tourist : tourists.values()) sendTo(tourist, "STOP|" + reason);
        writeResult(reason);
        System.out.println("[MANAGER] Планирование завершено: " + reason);
    }

    private void writeResult(String reason) {
        File dir = new File("result");
        if (!dir.exists()) dir.mkdirs();

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream("result/result.txt"), StandardCharsets.UTF_8))) {
            pw.println("=== План распределения снаряжения ===");
            pw.println("Причина завершения: " + reason);
            pw.println();

            for (Map.Entry<String, List<String>> e : distribution.entrySet()) {
                pw.printf("%s: %.2f кг%n", e.getKey(), bagWeight(e.getValue()));
                for (String item : e.getValue()) {
                    pw.printf("  - %s (%.2f кг)%n", item, itemWeights.get(item));
                }
                pw.println();
            }
            pw.printf("Средний вес: %.2f кг%n", calcAvg());
            pw.printf("Разброс: %.2f кг%n", calcDiff());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadItemsFromFile() {
        File f = new File("data/items.txt");
        if (!f.exists()) {
            System.err.println("[MANAGER] Не найден файл data/items.txt");
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+", 2);
                if (p.length != 2) continue;
                double weight = Double.parseDouble(p[0].replace(',', '.'));
                itemWeights.put(p[1], weight);
                itemAgentNames.put(p[1], "item_" + p[1]);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("[MANAGER] Загружено предметов: " + itemWeights.size());
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("manager");
        sd.setName("Backpack-Manager");
        dfd.addServices(sd);
        try { DFService.register(this, dfd); }
        catch (FIPAException e) { e.printStackTrace(); }
    }

    private double bagWeight(List<String> bag) {
        double sum = 0;
        for (String item : bag) sum += itemWeights.getOrDefault(item, 0.0);
        return sum;
    }

    private double calcAvg() {
        if (distribution.isEmpty()) return 0;
        double total = 0;
        for (List<String> bag : distribution.values()) total += bagWeight(bag);
        return total / distribution.size();
    }

    private double calcDiff() {
        if (distribution.isEmpty()) return 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (List<String> bag : distribution.values()) {
            double w = bagWeight(bag);
            min = Math.min(min, w);
            max = Math.max(max, w);
        }
        return max - min;
    }

    private String serializeWeights() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : itemWeights.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    private String serialize(List<String> bag) {
        if (bag == null || bag.isEmpty()) return "-";
        return String.join(";", bag);
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); }
        catch (FIPAException ignored) {}
    }
}
