import java.io.*;
import java.net.*;

public class MusicServer {
    private int port;
    private String musicFolder;
    private static final int DATA_SIZE = 1024;
    private static final int WINDOW_SIZE = 5;

    public MusicServer(int port, String folderPath) {
        this.port = port;
        this.musicFolder = folderPath;
    }

    public void start() {
        System.out.println("=== SERVIDOR UDP LISTO EN PUERTO " + port + " ===");
        File folder = new File(musicFolder);
        if (!folder.exists() || !folder.isDirectory()) {
            System.err.println("ERROR CRÍTICO: No encuentro la carpeta: " + folder.getAbsolutePath());
            return;
        } else {
            System.out.println("Carpeta OK: " + folder.getName());
            File[] files = folder.listFiles();
            if (files != null) {
                for (File f : files) if (f.isFile()) System.out.println("  - " + f.getName());
            }
        }

        try (DatagramSocket socket = new DatagramSocket(port)) {
            while (true) {
                // Buffer fresco para cada petición inicial
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                // Procesar en un hilo independiente
                new Thread(() -> handleRequest(socket, packet)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleRequest(DatagramSocket serverSocket, DatagramPacket requestPacket) {
        try {
            String message = new String(requestPacket.getData(), 0, requestPacket.getLength()).trim();
            String[] parts = message.split(":", 2);
            String command = parts[0];
            
            InetAddress clientIP = requestPacket.getAddress();
            int clientPort = requestPacket.getPort();

            if (command.equals("LIST")) {
                File folder = new File(musicFolder);
                StringBuilder sb = new StringBuilder("LIST_RES:");
                File[] files = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
                if (files != null) {
                    for (File f : files) sb.append(f.getName()).append(",");
                }
                sendResponse(serverSocket, sb.toString(), clientIP, clientPort);
            } 
            else if (command.equals("BUSCAR")) {
                String songName = parts.length > 1 ? parts[1].trim() : "";
                File file = findFileRobust(songName);
                if (file != null) {
                    sendResponse(serverSocket, "FOUND:" + port, clientIP, clientPort);
                }
            }
            else if (command.equals("PLAY")) {
                String songName = parts.length > 1 ? parts[1].trim() : "";
                System.out.println("Reproduciendo: " + songName + " para cliente " + clientPort);
                startStreamingGBN(songName, clientIP, clientPort);
            }

        } catch (Exception e) { e.printStackTrace(); }
    }

    private void sendResponse(DatagramSocket socket, String msg, InetAddress ip, int port) throws IOException {
        byte[] data = msg.getBytes();
        socket.send(new DatagramPacket(data, data.length, ip, port));
    }

    private File findFileRobust(String name) {
        File folder = new File(musicFolder);
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equalsIgnoreCase(name)) return file;
            }
        }
        return null;
    }

    // --- LÓGICA DE STREAMING CON REWIND Y SKIP ---
    private void startStreamingGBN(String filename, InetAddress clientIP, int clientPort) {
        File file = findFileRobust(filename);
        if (file == null) return;

        try (DatagramSocket streamSocket = new DatagramSocket()) {
            streamSocket.setSoTimeout(100); // Timeout breve para revisar comandos
            
            byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
            int totalPackets = (int) Math.ceil((double) fileBytes.length / DATA_SIZE);
            
            int base = 0; 
            int nextSeqNum = 0;
            boolean paused = false; 
            boolean finished = false;
            long lastCommandTime = 0;

            while (base < totalPackets && !finished) {
                // 1. Enviar ventana
                while (nextSeqNum < base + WINDOW_SIZE && nextSeqNum < totalPackets) {
                    // FIX CRÍTICO: Si está pausado, salimos del bucle de envío 
                    // para escuchar si llega el comando RESUME.
                    if (paused) {
                        break; 
                    }

                    byte[] packetData = createPacket(nextSeqNum, fileBytes);
                    streamSocket.send(new DatagramPacket(packetData, packetData.length, clientIP, clientPort));
                    nextSeqNum++;
                }

                // 2. Escuchar ACKs o Comandos
                try {
                    byte[] ackBuff = new byte[1024];
                    DatagramPacket ackP = new DatagramPacket(ackBuff, ackBuff.length);
                    streamSocket.receive(ackP);
                    String msg = new String(ackP.getData(), 0, ackP.getLength());
                    
                    if (msg.startsWith("ACK:")) {
                        int ack = Integer.parseInt(msg.split(":")[1]);
                        if (ack >= base) {
                            base = ack + 1;
                        }
                    }
                    else if (msg.equals("PAUSE")) {
                        paused = true;
                        System.out.println("Pausado para " + clientPort);
                    }
                    else if (msg.equals("RESUME")) {
                        paused = false;
                        System.out.println("Reanudado para " + clientPort);
                    }
                    else if (msg.startsWith("SKIP:")) { 
                        if (System.currentTimeMillis() - lastCommandTime > 200) {
                            base += 400; 
                            if (base >= totalPackets) base = totalPackets - 1;
                            nextSeqNum = base; 
                            System.out.println(">> Salto adelante a: " + base);
                            lastCommandTime = System.currentTimeMillis();
                        }
                    }
                    else if (msg.equals("REWIND")) {
                        if (System.currentTimeMillis() - lastCommandTime > 200) {
                            base -= 400; 
                            if (base < 0) base = 0; 
                            nextSeqNum = base;
                            System.out.println("<< Rebobinado a: " + base);
                            lastCommandTime = System.currentTimeMillis();
                        }
                    }
                    else if (msg.equals("STOP")) {
                        finished = true;
                    }
                    
                } catch (SocketTimeoutException e) {
                    // Timeout esperando ACK: reenviar ventana
                    // Si estaba pausado, esto ocurrirá constantemente (loop de espera), lo cual está bien.
                    nextSeqNum = base;
                }
            }
            
            streamSocket.send(new DatagramPacket("END".getBytes(), 3, clientIP, clientPort));
            System.out.println("Fin de canción para " + clientPort);

        } catch (Exception e) { e.printStackTrace(); }
    }

    private byte[] createPacket(int seqNum, byte[] fileData) {
        int start = seqNum * DATA_SIZE;
        int length = Math.min(DATA_SIZE, fileData.length - start);
        byte[] packet = new byte[4 + length];
        packet[0] = (byte) (seqNum >> 24); 
        packet[1] = (byte) (seqNum >> 16);
        packet[2] = (byte) (seqNum >> 8); 
        packet[3] = (byte) (seqNum);
        System.arraycopy(fileData, start, packet, 4, length);
        return packet;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USO: java MusicServer <PUERTO> <RUTA>");
            return;
        }
        new MusicServer(Integer.parseInt(args[0]), args[1]).start();
    }
}