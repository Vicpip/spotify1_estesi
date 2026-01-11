import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sound.sampled.*;

public class MusicClient {
    // Puertos de los 3 servidores conocidos
    private static final int[] SERVER_PORTS = {9001, 9002, 9003};
    private static final String SERVER_HOST = "localhost";
    
    // Estados del reproductor
    private DatagramSocket socket;
    private InetAddress currentServerIP;
    private int currentServerPort;
    private volatile boolean isPlaying = false;
    
    // Audio Buffer
    private BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>(50);
    
    public static void main(String[] args) {
        new MusicClient().start();
    }

    public void start() {
        Scanner scanner = new Scanner(System.in);
        try {
            socket = new DatagramSocket();
            System.out.println("=== CLIENTE MINI SPOTIFY (UDP + GBN) ===");
            
            while (true) {
                System.out.print("\nIntroduce nombre de canción (.wav) o 'EXIT': ");
                String input = scanner.nextLine();
                
                if (input.equalsIgnoreCase("EXIT")) break;
                
                // 1. Buscar en los 3 servidores
                int foundPort = searchSongInServers(input);
                
                if (foundPort != -1) {
                    System.out.println("Canción encontrada en servidor puerto: " + foundPort);
                    currentServerPort = foundPort; // El puerto del servidor principal, luego cambiará al puerto de streaming
                    playSong(input, foundPort);
                } else {
                    System.out.println("Canción no encontrada en ningún servidor.");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Busca la canción enviando paquetes a los 3 puertos conocidos
    private int searchSongInServers(String songName) {
        for (int port : SERVER_PORTS) {
            try {
                // "Descomponer nombre en bits" (Bytes UTF-8)
                String msg = "BUSCAR:" + songName;
                byte[] data = msg.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(SERVER_HOST), port);
                socket.send(packet);
                
                // Esperar respuesta brevemente
                socket.setSoTimeout(500); 
                byte[] buffer = new byte[256];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                
                try {
                    socket.receive(response);
                    String res = new String(response.getData(), 0, response.getLength());
                    if (res.startsWith("FOUND")) {
                        return port;
                    }
                } catch (SocketTimeoutException e) {
                    // No respondió este servidor, intentar siguiente
                }
                
            } catch (IOException e) {
                System.out.println("Error contactando servidor " + port);
            }
        }
        return -1;
    }

    private void playSong(String songName, int serverPort) {
        try {
            // Reiniciar estado
            socket.setSoTimeout(0); // Quitar timeout para el streaming
            audioQueue.clear();
            isPlaying = true;
            
            InetAddress ip = InetAddress.getByName(SERVER_HOST);

            // Enviar solicitud de PLAY
            String msg = "PLAY:" + songName;
            byte[] data = msg.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, ip, serverPort);
            socket.send(packet);

            // Iniciar Hilo de Interfaz de Control
            new Thread(this::controlLoop).start();
            
            // Iniciar Hilo de Reproducción de Audio
            Thread audioPlayerThread = new Thread(this::audioPlayerWorker);
            audioPlayerThread.start();

            // Lógica GBN Lado Receptor (Hilo actual)
            receiverGBNLoop();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Receptor Go-Back-N
    private void receiverGBNLoop() {
        int expectedSeq = 0;
        byte[] buffer = new byte[1028]; // 4 bytes header + 1024 data

        try {
            while (isPlaying) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                
                // Guardar la dirección del socket efímero del servidor para enviar controles
                if (currentServerPort != packet.getPort()) {
                    currentServerPort = packet.getPort();
                    currentServerIP = packet.getAddress();
                }

                // Verificar si es señal de fin
                String strData = new String(packet.getData(), 0, packet.getLength());
                if (strData.equals("END")) {
                    System.out.println("Fin de la canción.");
                    isPlaying = false;
                    break;
                }
                
                // Extraer SeqNum
                int seqNum = ((buffer[0] & 0xFF) << 24) | 
                             ((buffer[1] & 0xFF) << 16) | 
                             ((buffer[2] & 0xFF) << 8)  | 
                             (buffer[3] & 0xFF);

                // Lógica GBN: ¿Es el paquete que esperábamos?
                if (seqNum == expectedSeq) {
                    // Extraer audio
                    byte[] audioData = new byte[packet.getLength() - 4];
                    System.arraycopy(packet.getData(), 4, audioData, 0, audioData.length);
                    
                    // Meter en buffer de reproducción
                    audioQueue.offer(audioData);
                    
                    // Enviar ACK
                    sendControlMessage("ACK:" + expectedSeq);
                    expectedSeq++;
                } else {
                    // Paquete fuera de orden. Re-enviar ACK del último recibido correctamente
                    // (O en GBN simple, simplemente ignorar y esperar timeout del sender, 
                    // pero enviar ACK duplicado ayuda a acelerar recuperación)
                    sendControlMessage("ACK:" + (expectedSeq - 1));
                }
            }
        } catch (IOException e) {
            if (isPlaying) e.printStackTrace();
        }
    }

    // Hilo que saca bytes de la cola y los manda a los parlantes
    private void audioPlayerWorker() {
        try {
            // Formato estándar WAV (PCM Signed, 44100Hz, 16 bit, Stereo)
            // IMPORTANTE: Asegúrate que tus .wav tengan este formato o sonará ruido.
            AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            
            line.open(format);
            line.start();

            while (isPlaying || !audioQueue.isEmpty()) {
                byte[] data = audioQueue.poll(100, TimeUnit.MILLISECONDS);
                if (data != null) {
                    line.write(data, 0, data.length);
                }
            }
            line.drain();
            line.close();
            
        } catch (Exception e) {
            System.err.println("Error de audio (Probablemente formato WAV incompatible): " + e.getMessage());
        }
    }

    // Loop para leer comandos del usuario mientras suena la música
    private void controlLoop() {
        Scanner sc = new Scanner(System.in);
        System.out.println(">> Comandos: [P]ausa, [R]eanudar, [A]delantar, [S]ig. Canción");
        
        while (isPlaying) {
            String cmd = sc.nextLine().toUpperCase();
            if (!isPlaying) break; // Si la canción terminó sola

            switch (cmd) {
                case "P":
                    sendControlMessage("PAUSE");
                    break;
                case "R":
                    sendControlMessage("RESUME");
                    break;
                case "A":
                    sendControlMessage("SKIP:FAST");
                    // Limpiamos cola de audio local para que el salto se sienta inmediato
                    audioQueue.clear(); 
                    break;
                case "S":
                    sendControlMessage("STOP");
                    isPlaying = false; // Rompe el loop de recepción
                    break;
            }
        }
    }

    private void sendControlMessage(String msg) {
        try {
            if (currentServerIP != null) {
                byte[] data = msg.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, currentServerIP, currentServerPort);
                socket.send(packet);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}