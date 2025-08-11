package org.deskmate;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Main {
    private static JFrame mainFrame; // Keep reference for dialogs

    public static void main(String args[]) {
        SwingUtilities.invokeLater(Main::createAndShowGUI);
    }

    private static void createAndShowGUI() {
        mainFrame = new JFrame("DeskMate");
        mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mainFrame.setSize(600, 450); // taller for antivirus output dialog
        mainFrame.setLocationRelativeTo(null);

        // Disable resizing
        mainFrame.setResizable(false);

        // Always on top
        mainFrame.setAlwaysOnTop(true);

        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        panel.setLayout(new BorderLayout(10, 10));

        JLabel welcomeLabel = new JLabel("Welcome to DeskMate", SwingConstants.CENTER);
        welcomeLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        panel.add(welcomeLabel, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new GridLayout(4, 1, 10, 10)); // 4 buttons now

        JButton cleanCacheButton = new JButton("Clean Cache");
        cleanCacheButton.addActionListener(e -> showBrowserSelectionDialog());

        JButton antivirusCheckButton = new JButton("Antivirus Check");
        antivirusCheckButton.addActionListener(e -> antivirusCheck());

        JButton renameFilesButton = new JButton("Auto Rename/Move Files");
        renameFilesButton.addActionListener(e -> renameOrMoveFiles());

        JButton exitButton = new JButton("Exit");
        exitButton.addActionListener(e -> System.exit(0));

        buttonPanel.add(cleanCacheButton);
        buttonPanel.add(antivirusCheckButton);
        buttonPanel.add(renameFilesButton);
        buttonPanel.add(exitButton);

        panel.add(buttonPanel, BorderLayout.CENTER);

        mainFrame.setContentPane(panel);

        // Key listener for F11 fullscreen toggle and always-on-top toggle
        mainFrame.addKeyListener(new java.awt.event.KeyAdapter() {
            private boolean fullscreen = false;
            private Rectangle windowedBounds;

            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_F11) {
                    GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                    if (!fullscreen) {
                        // Save window bounds to restore later
                        windowedBounds = mainFrame.getBounds();

                        mainFrame.dispose();
                        mainFrame.setUndecorated(true);
                        mainFrame.setAlwaysOnTop(false); // disable always on top in fullscreen
                        mainFrame.setVisible(true);
                        device.setFullScreenWindow(mainFrame);

                        fullscreen = true;
                    } else {
                        device.setFullScreenWindow(null);
                        mainFrame.dispose();
                        mainFrame.setUndecorated(false);
                        mainFrame.setBounds(windowedBounds);
                        mainFrame.setAlwaysOnTop(true); // enable always on top again
                        mainFrame.setVisible(true);

                        fullscreen = false;
                    }
                }
            }
        });

        mainFrame.setVisible(true);

        // Request focus to receive key events
        mainFrame.requestFocusInWindow();
    }

    private static boolean isRunningAsAdmin() {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) {
                Process process = new ProcessBuilder("cmd", "/c", "net session").start();
                int exitCode = process.waitFor();
                return exitCode == 0;
            } else if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                Process process = new ProcessBuilder("id", "-u").start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String uid = reader.readLine();
                process.waitFor();
                return "0".equals(uid);
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void showBrowserSelectionDialog() {
        String[] options = {"Firefox", "Chrome", "Edge"};
        int choice = JOptionPane.showOptionDialog(
                mainFrame,
                "Select the browser cache to clean:",
                "Select Browser",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice == -1) return;

        String browser = options[choice];
        Path cachePath = null;

        switch (browser) {
            case "Chrome" -> cachePath = Paths.get(System.getenv("LOCALAPPDATA"),
                    "Google", "Chrome", "User Data", "Default", "Cache");
            case "Firefox" -> {
                cachePath = getFirefoxCachePath();
                if (cachePath == null) {
                    JOptionPane.showMessageDialog(mainFrame,
                            "Firefox cache folder not found!",
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
            case "Edge" -> cachePath = Paths.get(System.getenv("LOCALAPPDATA"),
                    "Microsoft", "Edge", "User Data", "Default", "Cache");
        }

        if (cachePath != null) {
            if (!Files.exists(cachePath)) {
                JOptionPane.showMessageDialog(mainFrame,
                        "Cache folder not found:\n" + cachePath,
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            cleanCacheWithProgress(browser, cachePath);
        }
    }

    private static Path getFirefoxCachePath() {
        String appData = System.getenv("APPDATA");
        if (appData == null) return null;

        Path profilesDir = Paths.get(appData, "Mozilla", "Firefox", "Profiles");
        if (!Files.exists(profilesDir) || !Files.isDirectory(profilesDir)) return null;

        try {
            List<Path> profiles = Files.list(profilesDir).filter(Files::isDirectory).toList();
            for (Path profile : profiles) {
                Path cache2 = profile.resolve("cache2");
                if (Files.exists(cache2) && Files.isDirectory(cache2)) return cache2;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void cleanCacheWithProgress(String browserName, Path cacheFolder) {
        JDialog dialog = new JDialog(mainFrame, "Cleaning Cache - " + browserName, true);
        dialog.setSize(400, 120);
        dialog.setLocationRelativeTo(mainFrame);
        dialog.setLayout(new BorderLayout(10, 10));

        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        dialog.add(progressBar, BorderLayout.CENTER);

        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                long totalFiles = Files.walk(cacheFolder).filter(Files::isRegularFile).count();
                if (totalFiles == 0) {
                    publish(100);
                    return null;
                }

                final long[] deletedCount = {0};
                Files.walk(cacheFolder).filter(Files::isRegularFile).forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception ignored) {}
                    deletedCount[0]++;
                    publish((int) ((deletedCount[0] * 100) / totalFiles));
                });
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                progressBar.setValue(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progressBar.setValue(100);
                JOptionPane.showMessageDialog(dialog,
                        "Cache cleaned for " + browserName + "!",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
            }
        };

        worker.execute();
        dialog.setVisible(true);
    }

    private static void renameOrMoveFiles() {
        String[] options = {"Rename", "Move"};
        int choice = JOptionPane.showOptionDialog(mainFrame,
                "Do you want to Rename or Move files?",
                "Rename or Move",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
        if (choice == -1) return;
        boolean isRename = (choice == 0);

        JFileChooser inputChooser = new JFileChooser();
        inputChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        inputChooser.setDialogTitle("Select Input Folder");
        if (inputChooser.showOpenDialog(mainFrame) != JFileChooser.APPROVE_OPTION) return;
        File inputFolder = inputChooser.getSelectedFile();

        JFileChooser outputChooser = new JFileChooser();
        outputChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        outputChooser.setDialogTitle("Select Output Folder");
        if (outputChooser.showOpenDialog(mainFrame) != JFileChooser.APPROVE_OPTION) return;
        File outputFolder = outputChooser.getSelectedFile();

        String fileTypesStr = JOptionPane.showInputDialog(mainFrame,
                "Enter file extensions (comma separated, e.g. txt,jpg,png):",
                "File Types", JOptionPane.QUESTION_MESSAGE);
        if (fileTypesStr == null || fileTypesStr.trim().isEmpty()) return;

        List<String> fileTypes = Arrays.stream(fileTypesStr.toLowerCase().split(","))
                .map(s -> s.startsWith(".") ? s : "." + s.trim())
                .collect(Collectors.toList());

        String namingPattern = JOptionPane.showInputDialog(mainFrame,
                "Enter naming pattern for new files (e.g. File_, Doc_, Image_):\n" +
                        "Files will be named with this pattern + sequential number (01, 02, ...)",
                "Naming Pattern", JOptionPane.QUESTION_MESSAGE);
        if (namingPattern == null) return;
        namingPattern = namingPattern.trim();
        if (namingPattern.isEmpty()) namingPattern = "DeskMate_";

        String action = isRename ? "Rename" : "Move";
        int confirm = JOptionPane.showConfirmDialog(mainFrame,
                String.format("%s files with extensions %s\nfrom:\n%s\nto:\n%s\nNaming pattern: %s\nProceed?",
                        action, fileTypes, inputFolder.getAbsolutePath(), outputFolder.getAbsolutePath(), namingPattern),
                "Confirm " + action, JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        runRenameMoveTask(action, inputFolder.toPath(), outputFolder.toPath(), fileTypes, isRename, namingPattern);
    }

    private static void runRenameMoveTask(String actionName, Path inputDir, Path outputDir,
                                          List<String> fileTypes, boolean isRename, String namingPattern) {
        JDialog dialog = new JDialog(mainFrame, actionName + " Files", true);
        dialog.setSize(450, 120);
        dialog.setLocationRelativeTo(mainFrame);
        dialog.setLayout(new BorderLayout(10, 10));

        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        dialog.add(progressBar, BorderLayout.CENTER);

        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                List<Path> filesToProcess = Files.walk(inputDir)
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String lower = p.getFileName().toString().toLowerCase();
                            return fileTypes.stream().anyMatch(lower::endsWith);
                        })
                        .collect(Collectors.toList());

                int totalFiles = filesToProcess.size();
                if (totalFiles == 0) {
                    JOptionPane.showMessageDialog(dialog, "No matching files found to " + actionName.toLowerCase() + ".", "No Files", JOptionPane.INFORMATION_MESSAGE);
                    return null;
                }

                int count = 1;
                for (Path srcFile : filesToProcess) {
                    try {
                        String ext = "";
                        String filename = srcFile.getFileName().toString();
                        int dotIndex = filename.lastIndexOf('.');
                        if (dotIndex >= 0) ext = filename.substring(dotIndex);
                        String newName = String.format("%s%02d%s", namingPattern, count, ext);
                        Path target = outputDir.resolve(newName);

                        if (isRename) {
                            Files.copy(srcFile, target, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.move(srcFile, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    publish((int) ((count / (float) totalFiles) * 100));
                    count++;
                }
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                progressBar.setValue(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progressBar.setValue(100);
                JOptionPane.showMessageDialog(dialog, actionName + " operation completed!", "Done", JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
            }
        };

        worker.execute();
        dialog.setVisible(true);
    }

    private static void antivirusCheck() {
        if (!isRunningAsAdmin()) {
            JOptionPane.showMessageDialog(mainFrame,
                    "The Antivirus Check feature requires administrator/root privileges.\n" +
                            "Please restart the application with elevated permissions and try again.\n\n" +
                            "Windows: Right-click and 'Run as administrator'\n" +
                            "Linux/Mac: Run using 'sudo' or as root user.",
                    "Administrator Privileges Required",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        String[] options = {"Windows", "Linux", "Mac"};
        int choice = JOptionPane.showOptionDialog(mainFrame,
                "Select your operating system:",
                "Antivirus Check",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
        if (choice == -1) return;

        String os = options[choice];
        runAntivirusCommands(os);
    }

    private static void runAntivirusCommands(String os) {
        String[] commands;
        switch (os) {
            case "Windows" -> commands = new String[]{
                    "cmd", "/c",
                    "sfc /scannow && " +
                            "DISM /Online /Cleanup-Image /RestoreHealth && " +
                            "wmic startup get caption,command && " +
                            "netstat -ano && " +
                            "tasklist | findstr [PID] && " +
                            "schtasks /query /fo LIST /v && " +
                            "sc query type= service state= all && " +
                            "netsh advfirewall reset && " +
                            "netsh int ip reset && " +
                            "netsh winsock reset"
            };
            case "Linux" -> commands = new String[]{
                    "/bin/bash", "-c",
                    "sudo apt-get update && sudo apt-get install clamav -y && sudo freshclam && sudo clamscan -r /"
            };
            case "Mac" -> commands = new String[]{
                    "/bin/bash", "-c",
                    "echo 'No built-in AV scanner. Consider installing ClamAV or using third-party software.'"
            };
            default -> {
                JOptionPane.showMessageDialog(mainFrame, "Unsupported OS", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        JDialog dialog = new JDialog(mainFrame, "Antivirus Check - " + os, true);
        dialog.setSize(600, 400);
        dialog.setLocationRelativeTo(mainFrame);
        dialog.setLayout(new BorderLayout());

        JTextArea outputArea = new JTextArea();
        outputArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(outputArea);
        dialog.add(scrollPane, BorderLayout.CENTER);

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        dialog.add(closeBtn, BorderLayout.SOUTH);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                try {
                    ProcessBuilder builder = new ProcessBuilder(commands);
                    builder.redirectErrorStream(true);
                    Process process = builder.start();

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            publish(line + "\n");
                        }
                    }
                    process.waitFor();
                } catch (Exception e) {
                    publish("Error running commands: " + e.getMessage());
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) {
                    outputArea.append(s);
                }
            }
        };

        worker.execute();
        dialog.setVisible(true);
    }

    private static void runTaskWithProgress(String taskName, int steps) {
        JDialog dialog = new JDialog(mainFrame, taskName, true);
        dialog.setSize(400, 120);
        dialog.setLocationRelativeTo(mainFrame);
        dialog.setLayout(new BorderLayout(10, 10));

        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        dialog.add(progressBar, BorderLayout.CENTER);

        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                for (int i = 0; i <= steps; i++) {
                    Thread.sleep(30); // simulate work
                    publish((int) ((i / (float) steps) * 100));
                }
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                progressBar.setValue(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                progressBar.setValue(100);
                JOptionPane.showMessageDialog(dialog, taskName + " Completed!");
                dialog.dispose();
            }
        };

        worker.execute();
        dialog.setVisible(true);
    }
}
