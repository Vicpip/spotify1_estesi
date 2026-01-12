import java.awt.*;
import java.net.*;
import java.util.concurrent.*;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class MusicClientGUI extends JFrame {

    private static final int[] SERVER_PORTS = {9001, 9002, 9003};
    private static final String SERVER_HOST = "127.0.0.1";
    
    private DatagramSocket socket;
    private InetAddress currentServerIP;
    private int currentServerPort; 
    
    private volatile boolean isPlaying = false;
    private volatile boolean isPaused = false;
    private volatile boolean isSkipping = false; 

    private volatile int lastAckedSeq = -1;
    private volatile int currentSeqNum = 0; 
    
    private BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>(500);
    private Thread receiverThread;
    private Thread playerThread;

    // UI
    private JTextField txtSearch;
    private JLabel lblStatus, lblTime; 
    private JProgressBar progressBar; 
    private JButton btnPlay, btnPause, btnSkip, btnRewind, btnSearch, btnRefresh;
    private JTextArea listArea;

    public MusicClientGUI() {
        super("Mini Spotify - Robust Client");
        initNetwork();
        initUI();
        refreshServers(); 
    }

    private void initNetwork() {
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(2000);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void initUI() {
        setSize(650, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // --- TOP ---
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.setBorder(new EmptyBorder(10, 10, 5, 10));

        JPanel searchBox = new JPanel(new BorderLayout(5, 0));
        txtSearch = new JTextField();
        btnSearch = new JButton("Buscar y Reproducir");
        searchBox.add(new JLabel("Canción: "), BorderLayout.WEST);
        searchBox.add(txtSearch, BorderLayout.CENTER);
        searchBox.add(btnSearch, BorderLayout.EAST);

        listArea = new JTextArea(8, 40);
        listArea.setEditable(false);
        JScrollPane scroll = new JScrollPane(listArea);
        btnRefresh = new JButton("Actualizar Lista");

        topPanel.add(searchBox, BorderLayout.NORTH);
        topPanel.add(scroll, BorderLayout.CENTER);
        topPanel.add(btnRefresh, BorderLayout.SOUTH);

        // --- CENTER ---
        JPanel centerPanel = new JPanel(new GridLayout(3, 1, 5, 5));
        centerPanel.setBorder(new EmptyBorder(10, 20, 10, 20));
        
        lblStatus = new JLabel("Listo.", SwingConstants.CENTER);
        lblStatus.setFont(new Font("Arial", Font.BOLD, 14));
        lblTime = new JLabel("00:00", SwingConstants.CENTER);
        lblTime.setFont(new Font("Monospaced", Font.BOLD, 16));
        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);

        centerPanel.add(lblStatus);
        centerPanel.add(lblTime);
        centerPanel.add(progressBar);

        // --- BOTTOM (CONTROLES) ---
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        
        btnRewind = new JButton("⏪ Atrasar");
        btnPause = new JButton("⏸ Pausa");
        btnPlay = new JButton("▶ Reanudar");
        btnSkip = new JButton("⏩ Adelantar");

        Dimension btnSize = new Dimension(100, 40);
        btnRewind.setPreferredSize(btnSize);
        btnPause.setPreferredSize(btnSize);
        btnPlay.setPreferredSize(btnSize);
        btnSkip.setPreferredSize(btnSize);
        
        btnRewind.setEnabled(false);
        btnPause.setEnabled(false);
        btnPlay.setEnabled(false);
        btnSkip.setEnabled(false);

        bottomPanel.add(btnRewind); 
        bottomPanel.add(btnPause);
        bottomPanel.add(btnPlay);
        bottomPanel.add(btnSkip);

        add(topPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        // --- ACCIONES ---
        btnRefresh.addActionListener(e -> refreshServers());
        btnSearch.addActionListener(e -> startSearch());
        
        // 1. ADELANTAR
        btnSkip.addActionListener(e -> {
            isSkipping = true; 
            // Enviamos 3 veces por si UDP pierde uno
            for(int i=0; i<3; i++) sendControlMessage("SKIP:FAST");
            audioQueue.clear();
            lblStatus.setText("Adelantando...");
        });

        // 2. ATRASAR
        btnRewind.addActionListener(e -> {
            isSkipping = true; 
            for(int i=0; i<3; i++) sendControlMessage("REWIND");
            audioQueue.clear(); 
            lblStatus.setText("Retrocediendo...");
        });

        // 3. PAUSA
        btnPause.addActionListener(e -> {
            sendControlMessage("PAUSE");
            isPaused = true;
            btnPause.setEnabled(false);
            btnPlay.setEnabled(true);
            lblStatus.setText("Pausado");
        });

        // 4. REANUDAR
        btnPlay.addActionListener(e -> {
            isPaused = false;
            // Enviamos RESUME varias veces para despertar al servidor
            for(int i=0; i<3; i++) sendControlMessage("RESUME");
            // Recordamos al servidor dónde nos quedamos
            if (lastAckedSeq != -1) sendControlMessage("ACK:" + lastAckedSeq);
            
            btnPause.setEnabled(true);
            btnPlay.setEnabled(false);
            lblStatus.setText("Reproduciendo...");
        });
    }

    // --- LÓGICA DE RED ---

    private void refreshServers() {
        listArea.setText("Buscando servidores...\n");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (int port : SERVER_PORTS) {
                try {
                    String msg = "LIST";
                    byte[] data = msg.getBytes();
                    DatagramPacket p = new DatagramPacket(data, data.length, InetAddress.getByName(SERVER_HOST), port);
                    socket.send(p);
                    byte[] buff = new byte[2048];
                    DatagramPacket res = new DatagramPacket(buff, buff.length);
                    socket.receive(res);
                    String text = new String(res.getData(), 0, res.getLength());
                    if (text.startsWith("LIST_RES:")) sb.append("Puerto ").append(port).append(": ").append(text.substring(9)).append("\n");
                } catch (Exception e) { sb.append("Puerto ").append(port).append(": Sin respuesta\n"); }
            }
            SwingUtilities.invokeLater(() -> listArea.setText(sb.toString()));
        }).start();
    }

    private void startSearch() {
        String song = txtSearch.getText().trim();
        if (song.isEmpty()) return;
        lblStatus.setText("Buscando...");
        btnSearch.setEnabled(false);
        new Thread(() -> {
            int port = -1;
            for (int p : SERVER_PORTS) {
                try {
                    String msg = "BUSCAR:" + song;
                    byte[] data = msg.getBytes();
                    DatagramPacket pack = new DatagramPacket(data, data.length, InetAddress.getByName(SERVER_HOST), p);
                    socket.send(pack);
                    byte[] buff = new byte[256];
                    DatagramPacket res = new DatagramPacket(buff, buff.length);
                    socket.receive(res);
                    if (new String(res.getData(), 0, res.getLength()).startsWith("FOUND")) { port = p; break; }
                } catch (Exception e) {}
            }
            int finalPort = port;
            SwingUtilities.invokeLater(() -> {
                if (finalPort != -1) startStreaming(song, finalPort);
                else {
                    lblStatus.setText("No encontrada.");
                    JOptionPane.showMessageDialog(this, "Canción no encontrada.");
                    btnSearch.setEnabled(true);
                }
            });
        }).start();
    }

    private void startStreaming(String song, int port) {
        isPlaying = false;
        isPaused = false;
        isSkipping = false;
        audioQueue.clear();
        progressBar.setValue(0);
        lblTime.setText("00:00");
        lastAckedSeq = -1;
        currentSeqNum = 0; 
        try { Thread.sleep(200); } catch(Exception e){}
        currentServerPort = port;
        isPlaying = true;
        
        btnRewind.setEnabled(true);
        btnPause.setEnabled(true);
        btnPlay.setEnabled(false);
        btnSkip.setEnabled(true);
        lblStatus.setText("Conectando...");

        try {
            currentServerIP = InetAddress.getByName(SERVER_HOST);
            socket.setSoTimeout(0); 
            String msg = "PLAY:" + song;
            byte[] data = msg.getBytes();
            DatagramPacket p = new DatagramPacket(data, data.length, currentServerIP, port);
            socket.send(p);
            receiverThread = new Thread(this::receiverLoop);
            playerThread = new Thread(this::audioPlayerWorker);
            receiverThread.start();
            playerThread.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void receiverLoop() {
        int expectedSeq = 0;
        byte[] buffer = new byte[1028];
        int packetsSinceSkip = 0; // CONTADOR DE SEGURIDAD

        while (isPlaying) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                
                // Si estamos pausados, drenamos el socket pero no procesamos.
                if (isPaused) {
                    continue;
                }

                if (packet.getPort() != currentServerPort) currentServerPort = packet.getPort();
                
                String strData = new String(packet.getData(), 0, packet.getLength());
                if (strData.startsWith("LIST") || strData.startsWith("FOUND")) continue;
                if (strData.equals("END")) {
                    isPlaying = false;
                    SwingUtilities.invokeLater(() -> {
                        lblStatus.setText("Fin.");
                        btnRewind.setEnabled(false);
                        btnPause.setEnabled(false);
                        btnSkip.setEnabled(false);
                        btnSearch.setEnabled(true);
                        progressBar.setValue(100);
                    });
                    break;
                }

                if (packet.getLength() > 4) {
                    int seqNum = ((buffer[0] & 0xFF) << 24) | ((buffer[1] & 0xFF) << 16) | 
                                 ((buffer[2] & 0xFF) << 8)  | (buffer[3] & 0xFF);

                    if (isSkipping) {
                        boolean jumpDetected = Math.abs(seqNum - expectedSeq) > 10;
                        if (jumpDetected) {
                            expectedSeq = seqNum; 
                            isSkipping = false;
                            packetsSinceSkip = 0;
                            SwingUtilities.invokeLater(() -> lblStatus.setText("Reproduciendo..."));
                        } else {
                            packetsSinceSkip++;
                            if (packetsSinceSkip > 20) {
                                isSkipping = false;
                                packetsSinceSkip = 0;
                                expectedSeq = seqNum; 
                                SwingUtilities.invokeLater(() -> lblStatus.setText("Sincronizado (Timeout)"));
                            } else {
                                continue;
                            }
                        }
                    }

                    if (seqNum == expectedSeq) {
                        byte[] audio = new byte[packet.getLength() - 4];
                        System.arraycopy(packet.getData(), 4, audio, 0, audio.length);
                        
                        audioQueue.offer(audio, 1, TimeUnit.SECONDS);
                        sendControlMessage("ACK:" + seqNum);
                        
                        lastAckedSeq = seqNum;
                        currentSeqNum = seqNum;
                        expectedSeq++;
                    } else {
                        sendControlMessage("ACK:" + (expectedSeq - 1));
                    }
                }
            } catch (Exception e) { if(isPlaying) e.printStackTrace(); }
        }
    }

    private void audioPlayerWorker() {
        SourceDataLine line = null;
        try {
            SwingUtilities.invokeLater(() -> lblStatus.setText("Buffering..."));
            while (audioQueue.size() < 10 && isPlaying) { Thread.sleep(50); }
            if (audioQueue.isEmpty()) return;

            byte[] header = audioQueue.peek();
            int channels = (header[22] & 0xFF) | ((header[23] & 0xFF) << 8);
            int rate = (header[24] & 0xFF) | ((header[25] & 0xFF) << 8) | 
                       ((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24);
            int bits = (header[34] & 0xFF) | ((header[35] & 0xFF) << 8);

            if (channels < 1 || channels > 2) channels = 2;
            if (rate < 4000 || rate > 192000) rate = 44100;
            if (bits != 8 && bits != 16) bits = 16;
            
            final int finalRate = rate;
            AudioFormat format = new AudioFormat(rate, bits, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            
            SwingUtilities.invokeLater(() -> lblStatus.setText("Reproduciendo (" + finalRate + "Hz)"));

            long bytesPerSecond = (long)(rate * channels * (bits / 8.0));
            if (bytesPerSecond == 0) bytesPerSecond = 176400; 
            
            while (isPlaying) {
                if (isPaused) { Thread.sleep(100); continue; }
                byte[] data = audioQueue.poll(50, TimeUnit.MILLISECONDS);
                if (data != null) {
                    line.write(data, 0, data.length);
                    
                    if (!isSkipping) {
                        long estimatedBytes = currentSeqNum * 1024L;
                        long seconds = estimatedBytes / bytesPerSecond;
                        long min = seconds / 60;
                        long sec = seconds % 60;
                        SwingUtilities.invokeLater(() -> {
                            lblTime.setText(String.format("%02d:%02d", min, sec));
                            progressBar.setValue((int)(seconds % 100)); 
                        });
                    }
                }
            }
            line.drain();
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> lblStatus.setText("Error Audio: " + e.getMessage()));
        } finally {
            if (line != null) line.close();
            try { socket.setSoTimeout(2000); } catch(Exception e){}
        }
    }

    private void sendControlMessage(String msg) {
        try {
            byte[] data = msg.getBytes();
            DatagramPacket p = new DatagramPacket(data, data.length, currentServerIP, currentServerPort);
            socket.send(p);
        } catch (Exception e) {}
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MusicClientGUI().setVisible(true));
    }
}