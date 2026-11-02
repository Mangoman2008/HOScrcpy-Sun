package forms;

import utils.SettingUtil;

import javax.swing.*;
import java.awt.event.ActionListener;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

public class SettingDialog extends JDialog {
    private JPanel contentPanel;
    private JRadioButton rbUseVideoStream;
    private JButton btnOK;
    private JButton btnCancel;
    private java.awt.Window owner;

    public SettingDialog(java.awt.Window owner) {
        super(owner, "设置", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        $$$setupUI$$$();
        setTitle("设置");
        this.owner = owner;
        setContentPane(contentPanel);
        btnOK.addActionListener(btnOKListener);
        btnCancel.addActionListener(btnCancelListener);
        boolean useVideoStream = SettingUtil.useVideoStream();
        rbUseVideoStream.setSelected(useVideoStream);
    }


    private final ActionListener btnOKListener = e -> {
        SettingUtil.setUseVideoStream(rbUseVideoStream.isSelected());
        SettingUtil.saveConfig();
        SettingDialog.this.setVisible(false);
    };

    private final ActionListener btnCancelListener = e -> {
        SettingDialog.this.setVisible(false);
    };

    private boolean checkSettingChange(){
        boolean useVideoStream = SettingUtil.useVideoStream();
        if(useVideoStream != rbUseVideoStream.isSelected()){
            return true;
        }
        return false;
    }

    /* ==== code generated from IntelliJ GUI Designer .form (recovered) ==== */
private void $$$setupUI$$$() {
        JButton jButton;
        JButton jButton2;
        JRadioButton jRadioButton;
        JPanel jPanel;
        this.contentPanel = jPanel = new JPanel();
        jPanel.setLayout(new GridBagLayout());
        JPanel jPanel2 = new JPanel();
        jPanel2.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.fill = 1;
        jPanel.add((Component)jPanel2, gridBagConstraints);
        JLabel jLabel = new JLabel();
        jLabel.setText("\u6295\u5c4f\u9009\u9879:");
        GridBagConstraints gridBagConstraints2 = new GridBagConstraints();
        gridBagConstraints2.gridx = 0;
        gridBagConstraints2.gridy = 0;
        gridBagConstraints2.anchor = 17;
        jPanel2.add((Component)jLabel, gridBagConstraints2);
        JPanel jPanel3 = new JPanel();
        jPanel3.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints3 = new GridBagConstraints();
        gridBagConstraints3.gridx = 1;
        gridBagConstraints3.gridy = 0;
        gridBagConstraints3.weightx = 1.0;
        gridBagConstraints3.weighty = 1.0;
        gridBagConstraints3.fill = 1;
        jPanel2.add((Component)jPanel3, gridBagConstraints3);
        this.rbUseVideoStream = jRadioButton = new JRadioButton();
        jRadioButton.setText("\u662f\u5426\u4f7f\u7528\u89c6\u9891\u6d41\u6295\u5c4f\u6a21\u5f0f");
        GridBagConstraints gridBagConstraints4 = new GridBagConstraints();
        gridBagConstraints4.gridx = 0;
        gridBagConstraints4.gridy = 0;
        gridBagConstraints4.anchor = 17;
        jPanel3.add((Component)jRadioButton, gridBagConstraints4);
        JPanel jPanel4 = new JPanel();
        jPanel4.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints5 = new GridBagConstraints();
        gridBagConstraints5.gridx = 0;
        gridBagConstraints5.gridy = 1;
        gridBagConstraints5.fill = 1;
        jPanel.add((Component)jPanel4, gridBagConstraints5);
        this.btnOK = jButton2 = new JButton();
        jButton2.setText("\u4fdd\u5b58");
        GridBagConstraints gridBagConstraints6 = new GridBagConstraints();
        gridBagConstraints6.gridx = 1;
        gridBagConstraints6.gridy = 0;
        gridBagConstraints6.fill = 2;
        jPanel4.add((Component)jButton2, gridBagConstraints6);
        this.btnCancel = jButton = new JButton();
        jButton.setText("\u53d6\u6d88");
        GridBagConstraints gridBagConstraints7 = new GridBagConstraints();
        gridBagConstraints7.gridx = 2;
        gridBagConstraints7.gridy = 0;
        gridBagConstraints7.fill = 2;
        jPanel4.add((Component)jButton, gridBagConstraints7);
        JPanel jPanel5 = new JPanel();
        GridBagConstraints gridBagConstraints8 = new GridBagConstraints();
        gridBagConstraints8.gridx = 0;
        gridBagConstraints8.gridy = 0;
        gridBagConstraints8.weightx = 1.0;
        gridBagConstraints8.fill = 2;
        jPanel4.add((Component)jPanel5, gridBagConstraints8);
    }
}
