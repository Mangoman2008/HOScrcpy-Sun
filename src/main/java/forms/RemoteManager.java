package forms;

import org.apache.commons.lang3.StringUtils;
import utils.MessageUtil;
import utils.SettingUtil;
import utils.TreeUtil;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.HashSet;

public class RemoteManager extends JDialog {
    private JButton btnAddRemoteIp;
    private JButton btnRemoveIp;
    private JTree tree;
    private JButton btnSave;
    private JButton btnCancel;
    private JPanel contentPanel;
    private DefaultMutableTreeNode ROOT;
    private JDialog dialog;
    private Frame owner;

    public RemoteManager(Frame owner, String title) {
        super(owner, title, true);
        $$$setupUI$$$();
        setContentPane(contentPanel);
        setModal(true);
        dialog = this;
        this.owner = owner;
        setTitle("管理远程IP");

        // 创建树节点
        for (String s : SettingUtil.getRemoteIp()) {
            if (StringUtils.isEmpty(s)) {
                continue;
            }
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(s);
            ROOT.add(node);
        }


        tree.setRootVisible(false);
        tree.setDragEnabled(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        TreeUtil.expandTree(tree, new TreePath(ROOT.getPath()));
        tree.updateUI();

        btnAddRemoteIp.addActionListener(btnAddIpListener);
        btnRemoveIp.addActionListener(btnRemoveIpListener);
        btnSave.addActionListener(btnSaveListener);
        btnCancel.addActionListener(btnCancelListener);
    }

    private final ActionListener btnCancelListener = e -> {
        dialog.setVisible(false);
    };

    private final ActionListener btnSaveListener = e -> {
        HashSet<String> hashSet = new HashSet<>();
        for (int i = 0; i < ROOT.getChildCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) ROOT.getChildAt(i);
            if (node.getUserObject() instanceof String) {
                String ip = (String) node.getUserObject();
                hashSet.add(ip);
            }
        }
        SettingUtil.getRemoteIpMap().clear();
        SettingUtil.getRemoteIpMap().addAll(hashSet);
        SettingUtil.saveConfig();
        dialog.setVisible(false);
    };

    private final ActionListener btnAddIpListener = e -> {
        String userInput = getUserInput().trim();
        if (StringUtils.isBlank(userInput)) {
            return;
        }
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(userInput);
        ROOT.add(node);
        TreeUtil.expandTree(tree, new TreePath(ROOT.getPath()));
        tree.updateUI();
    };

    private final ActionListener btnRemoveIpListener = e -> {
        // 获取当前选中的树节点
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (treeNode == null) {
            MessageUtil.showInfoMessage(owner, "请先选中需要删除的IP");
            return;
        }
        DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
        if (treeNode.getParent() != null) {
            model.removeNodeFromParent(treeNode);
        }
        tree.updateUI();
    };

    private String getUserInput() {
        JDialog dialog = new JDialog(this, "输入远程ip", true);
        dialog.setLocationRelativeTo(this);
        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        dialog.setContentPane(panel);

        JTextField jTextField = new JTextField(20);
        JButton jButton = new JButton("确定");
        panel.add(jTextField);
        panel.add(jButton);

        jButton.addActionListener(e -> {
            dialog.setVisible(false);
        });

        dialog.setSize(300, 120);
        dialog.setMinimumSize(new Dimension(300, 120));
        dialog.setVisible(true);
        return jTextField.getText();
    }

    private void createUIComponents() {
        ROOT = new DefaultMutableTreeNode();
        tree = new JTree(ROOT);
    }

    /* ==== code generated from IntelliJ GUI Designer .form (recovered) ==== */
private void $$$setupUI$$$() {
        JButton jButton;
        JButton jButton2;
        JButton jButton3;
        JButton jButton4;
        JPanel jPanel;
        this.createUIComponents();
        this.contentPanel = jPanel = new JPanel();
        jPanel.setLayout(new GridBagLayout());
        JPanel jPanel2 = new JPanel();
        jPanel2.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.fill = 1;
        jPanel.add((Component)jPanel2, gridBagConstraints);
        this.btnAddRemoteIp = jButton4 = new JButton();
        jButton4.setText("\u6dfb\u52a0IP");
        GridBagConstraints gridBagConstraints2 = new GridBagConstraints();
        gridBagConstraints2.gridx = 0;
        gridBagConstraints2.gridy = 0;
        gridBagConstraints2.fill = 2;
        jPanel2.add((Component)jButton4, gridBagConstraints2);
        this.btnRemoveIp = jButton3 = new JButton();
        jButton3.setText("\u5220\u9664IP");
        GridBagConstraints gridBagConstraints3 = new GridBagConstraints();
        gridBagConstraints3.gridx = 1;
        gridBagConstraints3.gridy = 0;
        gridBagConstraints3.fill = 2;
        jPanel2.add((Component)jButton3, gridBagConstraints3);
        JPanel jPanel3 = new JPanel();
        GridBagConstraints gridBagConstraints4 = new GridBagConstraints();
        gridBagConstraints4.gridx = 2;
        gridBagConstraints4.gridy = 0;
        gridBagConstraints4.weightx = 1.0;
        gridBagConstraints4.fill = 2;
        jPanel2.add((Component)jPanel3, gridBagConstraints4);
        JTree jTree = this.tree;
        GridBagConstraints gridBagConstraints5 = new GridBagConstraints();
        gridBagConstraints5.gridx = 0;
        gridBagConstraints5.gridy = 1;
        gridBagConstraints5.weightx = 1.0;
        gridBagConstraints5.weighty = 1.0;
        gridBagConstraints5.fill = 1;
        jPanel.add((Component)jTree, gridBagConstraints5);
        JPanel jPanel4 = new JPanel();
        jPanel4.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints6 = new GridBagConstraints();
        gridBagConstraints6.gridx = 0;
        gridBagConstraints6.gridy = 2;
        gridBagConstraints6.fill = 1;
        jPanel.add((Component)jPanel4, gridBagConstraints6);
        this.btnSave = jButton2 = new JButton();
        jButton2.setText("\u4fdd\u5b58");
        GridBagConstraints gridBagConstraints7 = new GridBagConstraints();
        gridBagConstraints7.gridx = 1;
        gridBagConstraints7.gridy = 0;
        gridBagConstraints7.fill = 2;
        jPanel4.add((Component)jButton2, gridBagConstraints7);
        this.btnCancel = jButton = new JButton();
        jButton.setText("\u53d6\u6d88");
        GridBagConstraints gridBagConstraints8 = new GridBagConstraints();
        gridBagConstraints8.gridx = 2;
        gridBagConstraints8.gridy = 0;
        gridBagConstraints8.fill = 2;
        jPanel4.add((Component)jButton, gridBagConstraints8);
        JPanel jPanel5 = new JPanel();
        GridBagConstraints gridBagConstraints9 = new GridBagConstraints();
        gridBagConstraints9.gridx = 0;
        gridBagConstraints9.gridy = 0;
        gridBagConstraints9.weightx = 1.0;
        gridBagConstraints9.fill = 2;
        jPanel4.add((Component)jPanel5, gridBagConstraints9);
    }
}
