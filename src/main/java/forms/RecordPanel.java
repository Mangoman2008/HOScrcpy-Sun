package forms;

import javax.swing.*;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

public class RecordPanel {

    public RecordPanel() {
        $$$setupUI$$$();
    }


    private JPanel contentPanel;
    private JPanel controlPanel;
    private JPanel addActionPanel;
    private JScrollPane actionInfoScrollPanel;
    private JPanel actionInfoPanel;
    private JButton btnNewStep;
    private JButton btnNewAction;
    private JButton btnSummarizeAction;
    private JButton btnPlay;
    private JButton btnGenerateScript;
    private JScrollPane stepScrollPanel;
    private JPanel stepPanel;



    /* ==== code generated from IntelliJ GUI Designer .form (recovered) ==== */
private void $$$setupUI$$$() {
        JPanel jPanel;
        JScrollPane jScrollPane;
        JButton jButton;
        JButton jButton2;
        JButton jButton3;
        JPanel jPanel2;
        JPanel jPanel3;
        JScrollPane jScrollPane2;
        JButton jButton4;
        JPanel jPanel4;
        JPanel jPanel5;
        this.contentPanel = jPanel5 = new JPanel();
        jPanel5.setLayout(new GridBagLayout());
        this.controlPanel = jPanel4 = new JPanel();
        jPanel4.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.fill = 1;
        jPanel5.add((Component)jPanel4, gridBagConstraints);
        this.btnGenerateScript = jButton4 = new JButton();
        jButton4.setText("\u751f\u6210\u811a\u672c");
        GridBagConstraints gridBagConstraints2 = new GridBagConstraints();
        gridBagConstraints2.gridx = 1;
        gridBagConstraints2.gridy = 0;
        gridBagConstraints2.fill = 2;
        jPanel4.add((Component)jButton4, gridBagConstraints2);
        JPanel jPanel6 = new JPanel();
        GridBagConstraints gridBagConstraints3 = new GridBagConstraints();
        gridBagConstraints3.gridx = 0;
        gridBagConstraints3.gridy = 0;
        gridBagConstraints3.weightx = 1.0;
        gridBagConstraints3.fill = 2;
        jPanel4.add((Component)jPanel6, gridBagConstraints3);
        this.stepScrollPanel = jScrollPane2 = new JScrollPane();
        GridBagConstraints gridBagConstraints4 = new GridBagConstraints();
        gridBagConstraints4.gridx = 0;
        gridBagConstraints4.gridy = 1;
        gridBagConstraints4.weightx = 1.0;
        gridBagConstraints4.weighty = 1.0;
        gridBagConstraints4.fill = 1;
        jPanel5.add((Component)jScrollPane2, gridBagConstraints4);
        this.stepPanel = jPanel3 = new JPanel();
        jPanel3.setLayout(new GridBagLayout());
        jScrollPane2.setViewportView(jPanel3);
        this.addActionPanel = jPanel2 = new JPanel();
        jPanel2.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints5 = new GridBagConstraints();
        gridBagConstraints5.gridx = 0;
        gridBagConstraints5.gridy = 2;
        gridBagConstraints5.weightx = 1.0;
        gridBagConstraints5.fill = 1;
        jPanel5.add((Component)jPanel2, gridBagConstraints5);
        this.btnNewStep = jButton3 = new JButton();
        jButton3.setText("\u65b0\u589e\u6b65\u9aa4");
        GridBagConstraints gridBagConstraints6 = new GridBagConstraints();
        gridBagConstraints6.gridx = 0;
        gridBagConstraints6.gridy = 0;
        gridBagConstraints6.weightx = 1.0;
        gridBagConstraints6.weighty = 1.0;
        gridBagConstraints6.fill = 2;
        jPanel2.add((Component)jButton3, gridBagConstraints6);
        this.btnNewAction = jButton2 = new JButton();
        jButton2.setText("\u65b0\u589eaction");
        GridBagConstraints gridBagConstraints7 = new GridBagConstraints();
        gridBagConstraints7.gridx = 1;
        gridBagConstraints7.gridy = 0;
        gridBagConstraints7.weightx = 1.0;
        gridBagConstraints7.weighty = 1.0;
        gridBagConstraints7.fill = 2;
        jPanel2.add((Component)jButton2, gridBagConstraints7);
        this.btnSummarizeAction = jButton = new JButton();
        jButton.setText("action\u5f52\u7eb3");
        GridBagConstraints gridBagConstraints8 = new GridBagConstraints();
        gridBagConstraints8.gridx = 2;
        gridBagConstraints8.gridy = 0;
        gridBagConstraints8.weightx = 1.0;
        gridBagConstraints8.weighty = 1.0;
        gridBagConstraints8.fill = 2;
        jPanel2.add((Component)jButton, gridBagConstraints8);
        this.actionInfoScrollPanel = jScrollPane = new JScrollPane();
        GridBagConstraints gridBagConstraints9 = new GridBagConstraints();
        gridBagConstraints9.gridx = 0;
        gridBagConstraints9.gridy = 3;
        gridBagConstraints9.weightx = 1.0;
        gridBagConstraints9.weighty = 1.0;
        gridBagConstraints9.fill = 1;
        jPanel5.add((Component)jScrollPane, gridBagConstraints9);
        this.actionInfoPanel = jPanel = new JPanel();
        jPanel.setLayout(new GridBagLayout());
        jScrollPane.setViewportView(jPanel);
    }
}
