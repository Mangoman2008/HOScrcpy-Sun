package forms;

import org.apache.commons.lang3.StringUtils;
import utils.MessageUtil;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.event.ActionListener;
import java.io.File;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

public class LayoutInputForm extends JFrame {
    private JPanel contentPanel;
    private JButton btnOK;
    private JButton btnCancel;
    private JTextField txtPicPath;
    private JButton btnPicSearch;
    private JTextField txtJsonPath;
    private JButton btnJsonSearch;
    private JPanel testPanel;
    private String picPath = "";
    private String jsonPath = "";
    private boolean isCancel = false;


    public LayoutInputForm() {
        $$$setupUI$$$();
        btnJsonSearch.addActionListener(btnJsonSearchListener);
        btnOK.addActionListener(btnOKListener);
        btnCancel.addActionListener(btnCancelListener);
        btnPicSearch.addActionListener(btnPicSearchListener);
        testPanel.setVisible(false);
        setSize(200, 300);
        setResizable(false);
    }


    public JComponent getContentPanel() {
        return contentPanel;
    }


    private ActionListener btnJsonSearchListener = e -> {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.addChoosableFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.getName().endsWith(".json");
            }

            @Override
            public String getDescription() {
                return "选择json文件";
            }
        });
        int dialog = fileChooser.showOpenDialog(contentPanel);
        if (dialog == JFileChooser.APPROVE_OPTION) {
            try {
                txtJsonPath.setText(fileChooser.getSelectedFile().getCanonicalPath());
            } catch (Exception ex) {
                MessageUtil.showInfoMessage(contentPanel,"获取json文件路径失败");
            }
        }
    };

    private ActionListener btnPicSearchListener = e -> {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.addChoosableFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.getName().endsWith(".png") || f.getName().endsWith(".jpeg") || f.getName().endsWith(".jpg");
            }

            @Override
            public String getDescription() {
                return "选择图片文件";
            }
        });
        int dialog = fileChooser.showOpenDialog(contentPanel);
        if (dialog == JFileChooser.APPROVE_OPTION) {
            try {
                txtPicPath.setText(fileChooser.getSelectedFile().getCanonicalPath());
            } catch (Exception ex) {
                MessageUtil.showInfoMessage(contentPanel, "获取图片文件路径失败");
            }
        }
    };


    public boolean checkPicPath() {
        picPath = txtPicPath.getText().trim();
        File f = new File(picPath);
        if (StringUtils.isEmpty(picPath) || !f.exists()) {
            MessageUtil.showInfoMessage(contentPanel,"图片路径不存在");
            return false;
        }
        if (!picPath.endsWith(".png") && !picPath.endsWith(".jpeg") && !picPath.endsWith(".jpg")) {
            MessageUtil.showInfoMessage(contentPanel,"请选择png,jpeg或jpg格式的图片");
            return false;
        }
        return true;
    }

    public boolean checkJsonPath() {
        jsonPath = txtJsonPath.getText().trim();
        File f = new File(jsonPath);
        if (StringUtils.isEmpty(jsonPath) || !f.exists()) {
            MessageUtil.showInfoMessage(contentPanel,"json文件路径不存在");
            return false;
        }
        if (!jsonPath.endsWith(".json")) {
            MessageUtil.showInfoMessage(contentPanel,"请选择正确的json文件");
            return false;
        }
        return true;
    }


    private final ActionListener btnOKListener = e -> {


    };

    private final ActionListener btnCancelListener = e -> {
        picPath = "";
        jsonPath = "";
        isCancel = true;
    };

    public File getJson() {
        return new File(txtJsonPath.getText().trim());
    }

    public File getPic() {
        return new File(txtPicPath.getText().trim());
    }

    /* ==== code generated from IntelliJ GUI Designer .form (recovered) ==== */
private void $$$setupUI$$$() {
        JButton jButton;
        JButton jButton2;
        JPanel jPanel;
        JButton jButton3;
        JTextField jTextField;
        JButton jButton4;
        JTextField jTextField2;
        JPanel jPanel2;
        this.contentPanel = jPanel2 = new JPanel();
        jPanel2.setLayout(new GridBagLayout());
        JPanel jPanel3 = new JPanel();
        jPanel3.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.fill = 1;
        gridBagConstraints.insets = new Insets(0, 10, 0, 10);
        jPanel2.add((Component)jPanel3, gridBagConstraints);
        JLabel jLabel = new JLabel();
        jLabel.setText("\u56fe\u7247\u8def\u5f84");
        GridBagConstraints gridBagConstraints2 = new GridBagConstraints();
        gridBagConstraints2.gridx = 0;
        gridBagConstraints2.gridy = 0;
        gridBagConstraints2.anchor = 17;
        jPanel3.add((Component)jLabel, gridBagConstraints2);
        this.txtPicPath = jTextField2 = new JTextField();
        GridBagConstraints gridBagConstraints3 = new GridBagConstraints();
        gridBagConstraints3.gridx = 1;
        gridBagConstraints3.gridy = 0;
        gridBagConstraints3.weightx = 1.0;
        gridBagConstraints3.weighty = 1.0;
        gridBagConstraints3.anchor = 17;
        gridBagConstraints3.fill = 2;
        jPanel3.add((Component)jTextField2, gridBagConstraints3);
        this.btnPicSearch = jButton4 = new JButton();
        jButton4.setText("\u6d4f\u89c8");
        GridBagConstraints gridBagConstraints4 = new GridBagConstraints();
        gridBagConstraints4.gridx = 2;
        gridBagConstraints4.gridy = 0;
        gridBagConstraints4.fill = 2;
        jPanel3.add((Component)jButton4, gridBagConstraints4);
        JPanel jPanel4 = new JPanel();
        jPanel4.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints5 = new GridBagConstraints();
        gridBagConstraints5.gridx = 0;
        gridBagConstraints5.gridy = 1;
        gridBagConstraints5.weightx = 1.0;
        gridBagConstraints5.weighty = 1.0;
        gridBagConstraints5.fill = 1;
        gridBagConstraints5.insets = new Insets(0, 10, 0, 10);
        jPanel2.add((Component)jPanel4, gridBagConstraints5);
        JLabel jLabel2 = new JLabel();
        jLabel2.setText("Json\u8def\u5f84");
        GridBagConstraints gridBagConstraints6 = new GridBagConstraints();
        gridBagConstraints6.gridx = 0;
        gridBagConstraints6.gridy = 0;
        gridBagConstraints6.anchor = 17;
        jPanel4.add((Component)jLabel2, gridBagConstraints6);
        this.txtJsonPath = jTextField = new JTextField();
        GridBagConstraints gridBagConstraints7 = new GridBagConstraints();
        gridBagConstraints7.gridx = 1;
        gridBagConstraints7.gridy = 0;
        gridBagConstraints7.weightx = 1.0;
        gridBagConstraints7.anchor = 17;
        gridBagConstraints7.fill = 2;
        jPanel4.add((Component)jTextField, gridBagConstraints7);
        this.btnJsonSearch = jButton3 = new JButton();
        jButton3.setText("\u6d4f\u89c8");
        GridBagConstraints gridBagConstraints8 = new GridBagConstraints();
        gridBagConstraints8.gridx = 2;
        gridBagConstraints8.gridy = 0;
        gridBagConstraints8.fill = 2;
        jPanel4.add((Component)jButton3, gridBagConstraints8);
        this.testPanel = jPanel = new JPanel();
        jPanel.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints9 = new GridBagConstraints();
        gridBagConstraints9.gridx = 0;
        gridBagConstraints9.gridy = 2;
        gridBagConstraints9.fill = 1;
        jPanel2.add((Component)jPanel, gridBagConstraints9);
        this.btnOK = jButton2 = new JButton();
        jButton2.setText("\u786e\u5b9a");
        GridBagConstraints gridBagConstraints10 = new GridBagConstraints();
        gridBagConstraints10.gridx = 0;
        gridBagConstraints10.gridy = 0;
        gridBagConstraints10.fill = 2;
        jPanel.add((Component)jButton2, gridBagConstraints10);
        JPanel jPanel5 = new JPanel();
        GridBagConstraints gridBagConstraints11 = new GridBagConstraints();
        gridBagConstraints11.gridx = 1;
        gridBagConstraints11.gridy = 0;
        gridBagConstraints11.fill = 2;
        jPanel.add((Component)jPanel5, gridBagConstraints11);
        this.btnCancel = jButton = new JButton();
        jButton.setText("\u53d6\u6d88");
        GridBagConstraints gridBagConstraints12 = new GridBagConstraints();
        gridBagConstraints12.gridx = 2;
        gridBagConstraints12.gridy = 0;
        gridBagConstraints12.fill = 2;
        jPanel.add((Component)jButton, gridBagConstraints12);
    }
}
