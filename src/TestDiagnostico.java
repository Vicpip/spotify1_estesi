import java.io.*;
import java.net.*;

public class TestDiagnostico {
    public static void main(String[] args) throws Exception {
        System.out.println("=== INICIANDO DIAGNÓSTICO ===");

        // PRUEBA DE ARCHIVOS
        String rutaPrueba = "../songs1"; // La ruta que estabas usando
        File carpeta = new File(rutaPrueba);
        System.out.println("\n[1] Verificando carpeta: " + carpeta.getAbsolutePath());
        
        if (!carpeta.exists()) {
            System.out.println("ERROR: La carpeta NO existe. Revisa la ruta.");
        } else {
            System.out.println("La carpeta existe.");
            File[] archivos = carpeta.listFiles();
            if (archivos == null || archivos.length == 0) {
                System.out.println(" ADVERTENCIA: La carpeta existe pero ESTÁ VACÍA.");
            } else {
                System.out.println(" Contenido detectado:");
                for (File f : archivos) {
                    System.out.println("   - " + f.getName() + (f.isHidden() ? " [Oculto]" : ""));
                }
            }
        }

        // PRUEBA DE RED (Loopback UDP)
        System.out.println("\n[2] Verificando red UDP Local (Puerto 9999)...");
        try {
            // Servidor temporal
            DatagramSocket server = new DatagramSocket(9999);
            server.setSoTimeout(2000);
            
            // Cliente temporal (hilo aparte)
            new Thread(() -> {
                try {
                    DatagramSocket client = new DatagramSocket();
                    byte[] data = "PING".getBytes();
                    DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), 9999);
                    client.send(packet);
                    client.close();
                } catch (Exception e) {
                    System.out.println(" Error enviando paquete: " + e.getMessage());
                }
            }).start();

            // Intentar recibir
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                server.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength());
                if (msg.equals("PING")) {
                    System.out.println(" ÉXITO: El tráfico UDP funciona correctamente en localhost.");
                }
            } catch (SocketTimeoutException e) {
                System.out.println(" FALLO CRÍTICO: Timeout. El Firewall de Windows está bloqueando Java.");
                System.out.println("   -> Solución: Desactiva el Firewall temporalmente o permite 'java.exe' en redes públicas/privadas.");
            }
            server.close();
        } catch (Exception e) {
            System.out.println(" Error abriendo puerto 9999: " + e.getMessage());
        }
    }
}