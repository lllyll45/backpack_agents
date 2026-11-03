package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

public class ItemAgent extends Agent {
    private double weight = 1.0;
    private String owner = "none";
    private String lockedBy = null;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            try {
                weight = Double.parseDouble(args[0].toString().replace(',', '.'));
            } catch (NumberFormatException ignored) {
                weight = 1.0;
            }
        }

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("item");
        sd.setName("Backpack-Item");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }

        System.out.printf("[ITEM %s] запущен, масса %.2f кг%n", getLocalName(), weight);

        addBehaviour(new jade.core.behaviours.TickerBehaviour(this, 1000) {
            private boolean registeredWithManager = false;

            @Override
            protected void onTick() {
                if (registeredWithManager) return;
                AID manager = findManager();
                if (manager != null) {
                    String itemName = getLocalName().startsWith("item_")
                            ? getLocalName().substring(5) : getLocalName();
                    sendTo(manager, "READY_ITEM|" + itemName + "|" + weight);
                    registeredWithManager = true;
                }
            }
        });

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
    }

    private void handleMessage(ACLMessage msg) {
        String content = msg.getContent() == null ? "" : msg.getContent();
        String sender = msg.getSender().getLocalName();

        if (content.startsWith("GET_INFO")) {
            reply(msg, "ITEM_INFO|" + getLocalName() + "|" + weight + "|" + owner);
        } else if (content.startsWith("SET_OWNER|")) {
            // Изменять владельца может только менеджер.
            AID manager = findManager();
            if (manager != null && msg.getSender().equals(manager)) {
                String[] p = content.split("\\|", 2);
                if (p.length == 2) {
                    owner = p[1];
                    lockedBy = null;
                    reply(msg, "OWNER_CHANGED|" + getLocalName() + "|" + owner);
                    System.out.println("[ITEM " + getLocalName() + "] владелец: " + owner);
                }
            } else {
                reply(msg, "ERROR|ONLY_MANAGER_CAN_SET_OWNER");
            }
        } else if (content.startsWith("LOCK_ITEM|")) {
            String[] p = content.split("\\|", 2);
            if (lockedBy == null || lockedBy.equals(sender)) {
                lockedBy = sender;
                reply(msg, "LOCK_OK|" + getLocalName());
            } else {
                reply(msg, "LOCK_REFUSE|" + getLocalName() + "|" + lockedBy);
            }
        } else if (content.startsWith("UNLOCK_ITEM")) {
            if (lockedBy != null && lockedBy.equals(sender)) {
                lockedBy = null;
                reply(msg, "UNLOCK_OK|" + getLocalName());
            } else {
                reply(msg, "UNLOCK_REFUSE|" + getLocalName());
            }
        }
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

    private void sendTo(AID receiver, String content) {
        ACLMessage m = new ACLMessage(ACLMessage.INFORM);
        m.addReceiver(receiver);
        m.setContent(content);
        send(m);
    }

    private void reply(ACLMessage original, String content) {
        ACLMessage r = original.createReply();
        r.setPerformative(ACLMessage.INFORM);
        r.setContent(content);
        send(r);
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException ignored) {}
        System.out.println("[ITEM " + getLocalName() + "] завершил работу");
    }
}
