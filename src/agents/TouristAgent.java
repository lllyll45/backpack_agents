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

import java.util.*;

public class TouristAgent extends Agent {
    private final List<String> myItems = new ArrayList<>();
    private final Set<String> triedPartners = new HashSet<>();

    private double myWeight;
    private double avgWeight;
    private double currentDiff;

    private AID managerAID;
    private String pendingPartner;
    private String pendingGive;
    private String pendingTake;
    private boolean waitingForInfo;
    private boolean waitingForDecision;
    private boolean stopped;
    private boolean reportedNoImprovement;

    @Override
    protected void setup() {
        registerInDF();
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

        // Повторный поиск менеджера: клиент может подключиться раньше его регистрации.
        addBehaviour(new TickerBehaviour(this, 1000) {
            @Override
            protected void onTick() {
                if (managerAID == null) {
                    managerAID = findManager();
                    if (managerAID != null) {
                        sendTo(managerAID, "READY_TOURIST|" + getLocalName());
                        System.out.println("[TOURIST " + getLocalName() + "] зарегистрирован у менеджера");
                    }
                }
            }
        });

        addBehaviour(new TickerBehaviour(this, 2000) {
            @Override
            protected void onTick() {
                if (stopped || managerAID == null
                        || waitingForInfo || waitingForDecision || reportedNoImprovement) return;
                askNextPartner();
            }
        });

        System.out.println("[TOURIST " + getLocalName() + "] запущен");
    }

    private void handleMessage(ACLMessage msg) {
        String content = msg.getContent() == null ? "" : msg.getContent();
        String sender = msg.getSender().getLocalName();

        if (content.startsWith("BAG_UPDATE|")) {
            String[] p = content.split("\\|", 5);
            if (p.length >= 5) {
                myItems.clear();
                if (!p[1].equals("-")) myItems.addAll(Arrays.asList(p[1].split(";")));
                avgWeight = parseDouble(p[2]);
                currentDiff = parseDouble(p[3]);
                itemWeights.clear();
                if (!p[4].isEmpty()) {
                    for (String pair : p[4].split(";")) {
                        String[] kv = pair.split(":", 2);
                        if (kv.length == 2) itemWeights.put(kv[0], parseDouble(kv[1]));
                    }
                }
                myWeight = calculateWeight(myItems);
                triedPartners.clear();
                reportedNoImprovement = false;
                resetPending();
                System.out.printf("[TOURIST %s] обновлён рюкзак: %.2f кг, разброс %.2f кг%n",
                        getLocalName(), myWeight, currentDiff);
            }
        } else if (content.startsWith("REQUEST_WEIGHT_INFO|")) {
            sendTo(msg.getSender(), "WEIGHT_INFO|" + getLocalName() + "|" +
                    myWeight + "|" + serialize(myItems));
        } else if (content.startsWith("WEIGHT_INFO|")) {
            handleWeightInfo(sender, content);
        } else if (content.startsWith("PROPOSE_SWAP|")) {
            handleProposal(sender, content);
        } else if (content.startsWith("ACCEPT_SWAP|")) {
            handleAccept(sender, content);
        } else if (content.startsWith("REJECT_SWAP|") || content.startsWith("SWAP_REJECTED|")) {
            triedPartners.add(sender);
            resetPending();
        } else if (content.startsWith("STOP|")) {
            stopped = true;
            System.out.println("[TOURIST " + getLocalName() + "] получил STOP");
        }
    }

    private void askNextPartner() {
        List<AID> partners = findPartners();
        if (partners.isEmpty()) {
            reportNoImprovement();
            return;
        }

        for (AID partner : partners) {
            if (triedPartners.contains(partner.getLocalName())) continue;
            pendingPartner = partner.getLocalName();
            waitingForInfo = true;
            sendTo(partner, "REQUEST_WEIGHT_INFO|" + getLocalName());
            return;
        }

        reportNoImprovement();
    }

    private void handleWeightInfo(String sender, String content) {
        if (!waitingForInfo || pendingPartner == null || !pendingPartner.equals(sender)) return;
        String[] p = content.split("\\|", 4);
        if (p.length < 4) {
            triedPartners.add(sender);
            resetPending();
            return;
        }

        double partnerWeight = parseDouble(p[2]);
        List<String> partnerItems = parseItems(p[3]);

        String bestGive = null;
        String bestTake = null;
        double bestScore = 0;

        double before = Math.abs(myWeight - avgWeight)
                + Math.abs(partnerWeight - avgWeight);

        for (String give : myItems) {
            for (String take : partnerItems) {
                double newMy = myWeight - weightOf(give) + weightOf(take);
                double newPartner = partnerWeight - weightOf(take) + weightOf(give);
                double after = Math.abs(newMy - avgWeight)
                        + Math.abs(newPartner - avgWeight);
                double improvement = before - after;
                if (improvement > bestScore + 1e-9) {
                    bestScore = improvement;
                    bestGive = give;
                    bestTake = take;
                }
            }
        }

        waitingForInfo = false;
        if (bestGive == null) {
            triedPartners.add(sender);
            resetPending();
            return;
        }

        pendingGive = bestGive;
        pendingTake = bestTake;
        waitingForDecision = true;
        sendTo(new AID(sender, AID.ISLOCALNAME),
                "PROPOSE_SWAP|" + getLocalName() + "|" + bestGive + "|" + bestTake);
    }

    private void handleProposal(String sender, String content) {
        // PROPOSE_SWAP|initiator|giveItem|takeItem
        String[] p = content.split("\\|", 4);
        if (p.length != 4 || waitingForDecision || waitingForInfo) {
            sendTo(new AID(sender, AID.ISLOCALNAME), "REJECT_SWAP|занят или некорректный запрос");
            return;
        }

        String partnerGive = p[2];
        String partnerTake = p[3];

        if (!myItems.contains(partnerTake)) {
            sendTo(new AID(sender, AID.ISLOCALNAME), "REJECT_SWAP|предмет уже отсутствует");
            return;
        }

        // Проверяем, что обмен не ухудшает отклонение нашего рюкзака от среднего веса.
        double newWeight = myWeight - weightOf(partnerTake) + weightOf(partnerGive);
        if (Math.abs(newWeight - avgWeight) > Math.abs(myWeight - avgWeight) + 1e-9) {
            sendTo(new AID(sender, AID.ISLOCALNAME), "REJECT_SWAP|мой весовой баланс ухудшается");
            return;
        }

        pendingPartner = sender;
        pendingGive = partnerTake;
        pendingTake = partnerGive;
        waitingForDecision = true;
        sendTo(new AID(sender, AID.ISLOCALNAME),
                "ACCEPT_SWAP|" + getLocalName() + "|" + partnerGive + "|" + partnerTake);
    }

    private void handleAccept(String sender, String content) {
        if (!waitingForDecision || pendingPartner == null || !pendingPartner.equals(sender)
                || pendingGive == null || pendingTake == null) return;

        if (managerAID != null) {
            sendTo(managerAID, "COMMIT_SWAP|" + getLocalName() + "|" + sender
                    + "|" + pendingGive + "|" + pendingTake);
        }
        triedPartners.add(sender);
        resetPending();
    }

    private void reportNoImprovement() {
        if (reportedNoImprovement || managerAID == null) return;
        reportedNoImprovement = true;
        sendTo(managerAID, "NO_IMPROVEMENT|" + getLocalName());
    }

    private void resetPending() {
        waitingForInfo = false;
        waitingForDecision = false;
        pendingPartner = null;
        pendingGive = null;
        pendingTake = null;
    }

    private double calculateWeight(List<String> items) {
        double sum = 0;
        for (String item : items) sum += weightOf(item);
        return sum;
    }

    private double weightOf(String item) {
        // Массы передаются в начальном распределении? Нет: их хранит менеджер.
        // Поэтому турист получает веса отдельным сообщением в BAG_UPDATE.
        return itemWeights.getOrDefault(item, 0.0);
    }

    private final Map<String, Double> itemWeights = new HashMap<>();

    private String serialize(List<String> items) {
        return items.isEmpty() ? "-" : String.join(";", items);
    }

    private List<String> parseItems(String value) {
        if (value == null || value.isEmpty() || value.equals("-")) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(value.split(";")));
    }

    private double parseDouble(String value) {
        try { return Double.parseDouble(value.replace(',', '.')); }
        catch (Exception e) { return 0; }
    }

    private void sendTo(AID receiver, String content) {
        ACLMessage m = new ACLMessage(ACLMessage.INFORM);
        m.addReceiver(receiver);
        m.setContent(content);
        send(m);
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("tourist");
        sd.setName("Backpack-Tourist");
        dfd.addServices(sd);
        try { DFService.register(this, dfd); }
        catch (FIPAException e) { e.printStackTrace(); }
    }

    private AID findManager() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("manager");
        template.addServices(sd);
        try {
            DFAgentDescription[] result = DFService.search(this, template);
            if (result.length > 0) return result[0].getName();
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        return null;
    }

    private List<AID> findPartners() {
        List<AID> result = new ArrayList<>();
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("tourist");
        template.addServices(sd);
        try {
            for (DFAgentDescription d : DFService.search(this, template)) {
                if (!d.getName().equals(getAID())) result.add(d.getName());
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); }
        catch (FIPAException ignored) {}
    }
}
