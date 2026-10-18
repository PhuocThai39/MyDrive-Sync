package vn.edu.pbl4sync.client.ui;

import vn.edu.pbl4sync.client.AgentService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WorkspacePanel extends JPanel {
    private final AgentService service;
    private final JComboBox<WorkspaceItem> selector = new JComboBox<>();
    private final DefaultTableModel filesModel = model("Name", "Size", "Version", "Modified By", "Modified At",
            "State");
    private final JTable filesTable = new JTable(filesModel);
    private final DefaultTableModel membersModel = model("User ID", "Username", "Role", "Read", "Write", "Modify",
            "Delete");
    private final JTable membersTable = new JTable(membersModel);
    private final DefaultTableModel activityModel = model("Time", "User", "Action", "Detail");
    private final JTable activityTable = new JTable(activityModel);
    private final JButton addMember = new JButton("Add Member");
    private final JButton editPermission = new JButton("Edit Permission");
    private final JButton removeMember = new JButton("Remove Member");
    private final JButton createWorkspace = new JButton("Create Workspace");
    private final JButton changeLeader = new JButton("Change Leader");
    private final JButton deleteWorkspace = new JButton("Delete Workspace");
    private List<Map<String, String>> fileRows = new ArrayList<>();
    private List<Map<String, String>> memberRows = new ArrayList<>();

    public WorkspacePanel(AgentService service) {
        super(new BorderLayout(10, 10));
        this.service = service;
        setBorder(new EmptyBorder(18, 18, 18, 18));
        build();
    }

    private void build() {
        JPanel top = new JPanel(new BorderLayout(8, 8));
        JLabel title = new JLabel("Workspaces");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        top.add(title, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        selector.setPreferredSize(new Dimension(260, 30));
        selector.addActionListener(e -> loadSelected());
        actions.add(selector);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshData());
        actions.add(refresh);
        createWorkspace.setVisible(service.isAdmin());
        createWorkspace.addActionListener(e -> createWorkspace());
        actions.add(createWorkspace);
        changeLeader.setVisible(service.isAdmin());
        changeLeader.addActionListener(e -> changeLeader());
        actions.add(changeLeader);
        deleteWorkspace.setVisible(service.isAdmin());
        deleteWorkspace.addActionListener(e -> deleteWorkspace());
        actions.add(deleteWorkspace);
        top.add(actions, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Files", filesTab());
        tabs.addTab("Members", membersTab());
        tabs.addTab("Activity", new JScrollPane(activityTable));
        add(tabs, BorderLayout.CENTER);
    }

    private JPanel filesTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(new JScrollPane(filesTable), BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton upload = new JButton("Upload");
        JButton copy = new JButton("Save a Copy");
        JButton delete = new JButton("Delete");
        JButton open = new JButton("Open Local Folder");
        JButton sync = new JButton("Sync Now");
        upload.addActionListener(e -> upload());
        copy.addActionListener(e -> saveCopy());
        delete.addActionListener(e -> deleteSelected());
        open.addActionListener(e -> openFolder());
        sync.addActionListener(e -> UiAsync.run(() -> {
            service.syncNow();
            return null;
        }, x -> loadSelected(), ex -> UiAsync.error(this, ex)));
        b.add(upload);
        b.add(copy);
        b.add(delete);
        b.add(open);
        b.add(sync);
        p.add(b, BorderLayout.SOUTH);
        return p;
    }

    private JPanel membersTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(new JScrollPane(membersTable), BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT));
        addMember.addActionListener(e -> addMember());
        editPermission.addActionListener(e -> editPermission());
        removeMember.addActionListener(e -> removeMember());
        b.add(addMember);
        b.add(editPermission);
        b.add(removeMember);
        p.add(b, BorderLayout.SOUTH);
        return p;
    }

    public void refreshData() {
        UiAsync.run(service::refreshWorkspaces, rows -> {
            WorkspaceItem previous = (WorkspaceItem) selector.getSelectedItem();
            long prev = previous == null ? -1 : previous.id();
            selector.removeAllItems();
            for (Map<String, String> r : rows)
                selector.addItem(new WorkspaceItem(r));
            if (prev > 0)
                for (int i = 0; i < selector.getItemCount(); i++)
                    if (selector.getItemAt(i).id() == prev)
                        selector.setSelectedIndex(i);
            if (selector.getItemCount() > 0 && selector.getSelectedIndex() < 0)
                selector.setSelectedIndex(0);
            loadSelected();
        }, ex -> UiAsync.error(this, ex));
    }

    private void loadSelected() {
        WorkspaceItem item = (WorkspaceItem) selector.getSelectedItem();
        if (item == null)
            return;
        boolean manager = service.canManageWorkspace(item.row());
        addMember.setEnabled(manager);
        editPermission.setEnabled(manager);
        removeMember.setEnabled(manager);
        UiAsync.run(() -> service.listFiles(item.id()), rows -> {
            fileRows = rows;
            fillFiles(rows);
        }, ex -> UiAsync.error(this, ex));
        UiAsync.run(() -> service.listMembers(item.id()), rows -> {
            memberRows = rows;
            fillMembers(rows);
        }, ex -> UiAsync.error(this, ex));
        UiAsync.run(() -> service.listActivity(item.id()), this::fillActivity, ex -> UiAsync.error(this, ex));
    }

    private void fillFiles(List<Map<String, String>> rows) {
        filesModel.setRowCount(0);
        for (var r : rows)
            filesModel.addRow(new Object[] { r.get("relative_path"), r.get("size"), r.get("version"),
                    r.get("modified_by"), r.get("modified_at"), truthy(r.get("deleted")) ? "Deleted" : "Synced" });
    }

    private void fillMembers(List<Map<String, String>> rows) {
        membersModel.setRowCount(0);
        for (var r : rows)
            membersModel.addRow(new Object[] { r.get("user_id"), r.get("username"), r.get("workspace_role"),
                    r.get("can_read"), r.get("can_write"), r.get("can_modify"), r.get("can_delete") });
    }

    private void fillActivity(List<Map<String, String>> rows) {
        activityModel.setRowCount(0);
        for (var r : rows)
            activityModel
                    .addRow(new Object[] { r.get("created_at"), r.get("username"), r.get("action"), r.get("detail") });
    }

    private void upload() {
        WorkspaceItem w = selected();
        if (w == null)
            return;
        if (!service.allowed(w.row(), "CREATE")) {
            warn("No WRITE permission");
            return;
        }
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            UiAsync.run(() -> {
                service.uploadFile(w.id(), fc.getSelectedFile().toPath());
                return null;
            }, x -> {
            }, ex -> UiAsync.error(this, ex));
    }

    private void saveCopy() {
        WorkspaceItem w = selected();
        Map<String, String> f = selectedFile();
        if (w == null || f == null)
            return;
        if (truthy(f.get("deleted"))) {
            warn("File is deleted");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(Path.of(f.get("relative_path")).getFileName().toString()));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)
            UiAsync.run(() -> {
                service.saveCopy(w.id(), f.get("relative_path"), fc.getSelectedFile().toPath());
                return null;
            }, x -> {
            }, ex -> UiAsync.error(this, ex));
    }

    private void deleteSelected() {
        WorkspaceItem w = selected();
        Map<String, String> f = selectedFile();
        if (w == null || f == null)
            return;
        if (!service.allowed(w.row(), "DELETE")) {
            warn("No DELETE permission");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete " + f.get("relative_path") + " for all workspace members?",
                "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
            UiAsync.run(() -> {
                service.deleteWorkspaceFile(w.id(), f.get("relative_path"));
                return null;
            }, x -> loadSelected(), ex -> UiAsync.error(this, ex));
    }

    private void openFolder() {
        WorkspaceItem w = selected();
        if (w != null)
            UiAsync.run(() -> {
                service.openWorkspace(w.id());
                return null;
            }, x -> {
            }, ex -> UiAsync.error(this, ex));
    }

    private void createWorkspace() {
        JTextField name = new JTextField();
        JTextField leader = new JTextField();
        Object[] form = { "Workspace name", name, "Leader username", leader };
        if (JOptionPane.showConfirmDialog(this, form, "Create Workspace",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            UiAsync.run(() -> {
                service.createWorkspace(name.getText().trim(), leader.getText().trim());
                return null;
            }, x -> refreshData(), ex -> UiAsync.error(this, ex));
    }

    private void changeLeader() {
        WorkspaceItem w = selected();
        if (w == null)
            return;
        String u = JOptionPane.showInputDialog(this, "New leader username:", w.row().getOrDefault("leader", ""));
        if (u == null || u.isBlank())
            return;
        UiAsync.run(() -> {
            service.changeLeader(w.id(), u.trim());
            return null;
        }, x -> refreshData(), ex -> UiAsync.error(this, ex));
    }

    private void deleteWorkspace() {
        WorkspaceItem w = selected();
        if (w == null)
            return;
        if (JOptionPane.showConfirmDialog(this,
                "Delete workspace '" + w.row().get("name")
                        + "'? Metadata will be removed; local folders are kept for safety.",
                "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
            UiAsync.run(() -> {
                service.deleteWorkspace(w.id());
                return null;
            }, x -> refreshData(), ex -> UiAsync.error(this, ex));
    }

    private void addMember() {
        WorkspaceItem w = selected();
        if (w == null)
            return;
        PermissionDialog d = new PermissionDialog(SwingUtilities.getWindowAncestor(this), "Add Member", true, null);
        d.setVisible(true);
        if (!d.approved())
            return;
        UiAsync.run(() -> {
            service.addMember(w.id(), d.username(), d.read(), d.write(), d.modify(), d.delete());
            return null;
        }, x -> loadSelected(), ex -> UiAsync.error(this, ex));
    }

    private void editPermission() {
        WorkspaceItem w = selected();
        Map<String, String> m = selectedMember();
        if (w == null || m == null)
            return;
        if ("LEADER".equalsIgnoreCase(m.get("workspace_role"))) {
            warn("Leader always has full permission");
            return;
        }
        PermissionDialog d = new PermissionDialog(SwingUtilities.getWindowAncestor(this), "Edit Permission", false, m);
        d.setVisible(true);
        if (!d.approved())
            return;
        UiAsync.run(() -> {
            service.updatePermission(w.id(), Long.parseLong(m.get("user_id")), d.read(), d.write(), d.modify(),
                    d.delete());
            return null;
        }, x -> loadSelected(), ex -> UiAsync.error(this, ex));
    }

    private void removeMember() {
        WorkspaceItem w = selected();
        Map<String, String> m = selectedMember();
        if (w == null || m == null)
            return;
        if ("LEADER".equalsIgnoreCase(m.get("workspace_role"))) {
            warn("Cannot remove the workspace leader");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Remove " + m.get("username") + "?", "Confirm",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
            UiAsync.run(() -> {
                service.removeMember(w.id(), Long.parseLong(m.get("user_id")));
                return null;
            }, x -> loadSelected(), ex -> UiAsync.error(this, ex));
    }

    private WorkspaceItem selected() {
        return (WorkspaceItem) selector.getSelectedItem();
    }

    private Map<String, String> selectedFile() {
        int i = filesTable.getSelectedRow();
        return i < 0 || i >= fileRows.size() ? null : fileRows.get(i);
    }

    private Map<String, String> selectedMember() {
        int i = membersTable.getSelectedRow();
        return i < 0 || i >= memberRows.size() ? null : memberRows.get(i);
    }

    private static DefaultTableModel model(String... c) {
        return new DefaultTableModel(c, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    private boolean truthy(String s) {
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private void warn(String s) {
        JOptionPane.showMessageDialog(this, s, "Notice", JOptionPane.WARNING_MESSAGE);
    }

    private record WorkspaceItem(Map<String, String> row) {
        long id() {
            try {
                return Long.parseLong(row.get("id"));
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public String toString() {
            return row.get("name") + "  [" + row.getOrDefault("workspace_role", "") + "]";
        }
    }

    private static class PermissionDialog extends JDialog {
        private final JTextField username = new JTextField();
        private final JCheckBox read = new JCheckBox("Read", true), write = new JCheckBox("Write"),
                modify = new JCheckBox("Modify"), delete = new JCheckBox("Delete");
        private boolean approved;

        PermissionDialog(Window owner, String title, boolean showUsername, Map<String, String> existing) {
            super(owner, title, ModalityType.APPLICATION_MODAL);
            setSize(340, 300);
            setLocationRelativeTo(owner);
            JPanel p = new JPanel(new GridLayout(0, 1, 6, 6));
            p.setBorder(new EmptyBorder(14, 14, 14, 14));
            if (showUsername) {
                p.add(new JLabel("Username"));
                p.add(username);
            }
            if (existing != null) {
                read.setSelected(t(existing.get("can_read")));
                write.setSelected(t(existing.get("can_write")));
                modify.setSelected(t(existing.get("can_modify")));
                delete.setSelected(t(existing.get("can_delete")));
            }
            p.add(read);
            p.add(write);
            p.add(modify);
            p.add(delete);
            JButton ok = new JButton("Save");
            ok.addActionListener(e -> {
                approved = true;
                dispose();
            });
            p.add(ok);
            setContentPane(p);
        }

        boolean approved() {
            return approved;
        }

        String username() {
            return username.getText().trim();
        }

        boolean read() {
            return read.isSelected();
        }

        boolean write() {
            return write.isSelected();
        }

        boolean modify() {
            return modify.isSelected();
        }

        boolean delete() {
            return delete.isSelected();
        }

        static boolean t(String s) {
            return "1".equals(s) || "true".equalsIgnoreCase(s);
        }
    }
}
