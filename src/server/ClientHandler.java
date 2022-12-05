package server;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Класс обработчика клиента, реализующий интерфейс {@code Runnable}.
 * <p>
 * Метод {@code run()} переопределен для обработки запросов клиента в
 * отдельном потоке.
 *
 * @author Kirill Chezlov
 * @version 1.1
 */
public class ClientHandler implements Runnable {

    public static ArrayList<ClientHandler> clientHandlers = new ArrayList<>();
    private Socket socket;
    private ObjectOutputStream objectOutputStream;
    private ObjectInputStream objectInputStream;
    private EmailSender emailSender;
    private PGP pgp;
    private String serverName;
    private String clientUsername;
    private String clientPassword;
    private String clientEmail;
    private String serverPublicKey;
    private String serverPrivateKey;
    String clientPublicKey;
    static int severNum = 0;

    /**
     * Конструктор класса {@code ClientHandler}. Определяет
     * потоки ввода и вывода и pgp криптографер для дальнейшей работы.
     * @param socket сокет клиента
     */
    public ClientHandler(Socket socket) {
        this.socket = socket;
        severNum += 1;
        serverName = "sever_" + severNum;

        pgp = new PGP(serverName);
        serverPublicKey = getStringFromFile(pgp.getPublicKeyFilepath(serverName));
        serverPrivateKey = getStringFromFile(pgp.getPrivateKeyFilepath(serverName));

        try {
            objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
            objectInputStream = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            System.err.println("Ошибка создания I/O потоков: " + e);
        }

    }

    /**
     * Переопределенный метод {@code run()} является онсновным
     * при работе обработчика клиентов. Он запускает вспомогательные
     * методы-обработчики.
     */
    @Override
    public void run() {
        try {
            // получение публичного ключа клиента
            clientPublicKey = (String) objectInputStream.readObject();

            //отправка публичного ключа сервера клиенту
            objectOutputStream.writeObject(serverPublicKey);
            objectOutputStream.flush();

        } catch (Exception e) {
            System.out.println("Ошибка обмена ключами: " + e);
        }

        try {
            // ожидание получения имени клиета
            String username = (String) objectInputStream.readObject();
            this.clientUsername = pgp.decryptString(username, serverName);
            System.out.println(clientUsername);

            // ожидание получения email клиента
            String email = (String) objectInputStream.readObject();
            this.clientEmail = pgp.decryptString(email, serverName);
            emailSender = new EmailSender(clientEmail);

            // сохранение ключа в файл
            writeStringToFile(clientPublicKey, clientUsername);

        } catch (IOException | ClassNotFoundException e) {
            closeEverything();
            System.out.println("Ошибка получения имени клиента!");
        }

        // добавление подключившегося клиента в общий список
        clientHandlers.add(this);
        broadcastMessage(clientUsername + " has connected.", true);

        listenForMessage();

    }

    /**
     * Постоянно прослушивает входной поток и ожидает получения сообщений.
     * При поучении собщения рассылает его всем клиентам
     */
    public void listenForMessage() {
        String messageFromClient;

        while (socket.isConnected()) {
            try {
                messageFromClient = (String) objectInputStream.readObject();
                broadcastMessage(pgp.decryptString(messageFromClient, serverName), false);
            } catch (IOException | ClassNotFoundException e) {
                removeClientHandler();
                break;
            }
        }
    }

    /**
     * Рассылает сообщение всем подключенным клиентам, кроме отправившего
     * @param messageToSend сообщение
     * @param isService если {@code true}, то сообщение отправляется от
     *                  имени сервера
     */
    public void broadcastMessage(String messageToSend, boolean isService) {
        String senderUsername;
        SimpleDateFormat formatter = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        String date = formatter.format(new Date());

        for (ClientHandler clientHandler : clientHandlers) {
            try {
                if (!clientHandler.clientUsername.equals(clientUsername)) {

                    if (!isService) {
                        senderUsername = clientUsername;
                    } else senderUsername = "SERVER";

                    String msg = "[" + date + "] " + senderUsername + ": " + messageToSend;

                    clientHandler.objectOutputStream.writeObject(pgp.encryptString(msg, clientHandler.clientUsername));
                    clientHandler.objectOutputStream.flush();

                }
            } catch (IOException e) {
                closeEverything();
            }

        }

    }

    /**
     * позволяет получить весь текст из файла с заданным путем
     * @param path путь к файлу
     * @return String, содержащий весь текст из файла
     */
    private String getStringFromFile(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)));
        } catch (IOException e) {
            System.err.println("Ошибка чтения файла: " + e);
        }
        return null;
    }

    /**
     * позволяет записать текст в файл, содержащий в названии
     * имя пользователя-владельца
     * @param str текст для записи в файл
     * @param username имя пользователя-владельца файла
     */
    private void writeStringToFile(String str, String username) {
        try {
            String path = "src/server/res/PublicKey_" + username + ".pgp";
            BufferedWriter writer = new BufferedWriter(new FileWriter(path));
            writer.write(str);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            System.err.println("Ошибка записи ключа в файл: " + e);
        }
    }

    /**
     * Удаляет пользователя из списка подключенных и уведомляет
     * об этом всех в чате
     */
    public void removeClientHandler() {
        clientHandlers.remove(this);
        broadcastMessage(clientUsername + " has disconnected!", true);
    }

    /**
     * Закрывает все сокеты и потоки ввода/вывода
     */
    public void closeEverything() {

        removeClientHandler();
        try {
            if (objectInputStream != null) {
                objectInputStream.close();
            }
            if (objectOutputStream != null) {
                objectOutputStream.close();
            }
            if (socket != null) {
                socket.close();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}
