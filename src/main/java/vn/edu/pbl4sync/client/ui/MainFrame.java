package vn.edu.pbl4sync.client.ui;

import vn.edu.pbl4sync.client.AgentService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainFrame extends JFrame {
    private final AgentService service;
    private final JPanel cards = new JPanel(new CardLayout());
    private final JLabel connection = new JLabel();
    private final JLabel message = new JLabel("Ready");
    private final Map<String, Runnable> refreshers = new LinkedHashMap<>();
    private boolean warningPopupShown = false;
    private boolean errorPopupShown = false;

    public MainFrame(AgentService service) {
        super("PBL4 Company Sync - " + service.username());
        this.service = service;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(1180, 720);
        setMinimumSize(new Dimension(980, 620));
        setLocationRelativeTo(null);
        build();
        service.setNotificationListener((level, text) -> SwingUtilities.invokeLater(() -> notifyUser(level, text)));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                service.close();
                dispose();
                System.exit(0);
            }
        });
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout());
        setContentPane(root);
        JPanel sidebar = new JPanel();
        sidebar.setPreferredSize(new Dimension(180, 0));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(new EmptyBorder(15, 12, 15, 12));
        JLabel brand = new JLabel("COMPANY SYNC");
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 17f));
        brand.setAlignmentX(Component.CENTER_ALIGNMENT);
        sidebar.add(brand);
        sidebar.add(Box.createVerticalStrut(18));

        DashboardPanel dashboard = new DashboardPanel(service);
        WorkspacePanel workspaces = new WorkspacePanel(service);
        ActivityPanel activity = new ActivityPanel(service);
        SettingsPanel settings = new SettingsPanel(service);
        addCard(sidebar, "Dashboard", dashboard, dashboard::refreshData);
        addCard(sidebar, "Workspaces", workspaces, workspaces::refreshData);
        if (service.isAdmin()) {
            UsersPanel users = new UsersPanel(service);
            AgentsPanel agents = new AgentsPanel(service);
            addCard(sidebar, "Users", users, users::refreshData);
            addCard(sidebar, "Agents", agents, agents::refreshData);
        }
        addCard(sidebar, "Activity", activity, activity::refreshData);
        addCard(sidebar, "Settings", settings, settings::refreshData);
        sidebar.add(Box.createVerticalGlue());
        JLabel who = new JLabel("User: " + service.username() + (service.isAdmin() ? " (ADMIN)" : ""));
        who.setAlignmentX(Component.CENTER_ALIGNMENT);
        sidebar.add(who);
        root.add(sidebar, BorderLayout.WEST);
        root.add(cards, BorderLayout.CENTER);

        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(new EmptyBorder(5, 10, 5, 10));
        connection.setText("● Connected to " + service.config().host() + ":" + service.config().port());
        status.add(connection, BorderLayout.WEST);
        status.add(message, BorderLayout.EAST);
        root.add(status, BorderLayout.SOUTH);

        show("Dashboard");
    }

    private void addCard(JPanel sidebar, String name, JComponent panel, Runnable refresh) {
        JButton b = new JButton(name);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.addActionListener(e -> show(name));
        sidebar.add(b);
        sidebar.add(Box.createVerticalStrut(6));
        cards.add(panel, name);
        refreshers.put(name, refresh);
    }

    private void show(String name) {
        ((CardLayout) cards.getLayout()).show(cards, name);
        Runnable r = refreshers.get(name);
        if (r != null)
            r.run();
    }

    private void notifyUser(String level, String text) {
        message.setText(text);
        if ("ERROR".equalsIgnoreCase(level)) {
            if (errorPopupShown) {
                return;
            }
            errorPopupShown = true;
            JOptionPane.showMessageDialog(this, text, "Sync error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if ("WARN".equalsIgnoreCase(level)) {
            if (warningPopupShown) {
                return;
            }
            warningPopupShown = true;
            JOptionPane.showMessageDialog(this, text, "Sync warning", JOptionPane.WARNING_MESSAGE);
        }
    }
}
