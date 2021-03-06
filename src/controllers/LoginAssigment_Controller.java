package controllers;

import exceptions.EmailAlreadyRegisteredException;
import exceptions.EmailNotRegisteredException;
import exceptions.IncorrectEmailException;
import exceptions.UserAlreadyExistsException;
import models.FamilyGroupManager;
import models.User;
import net.Connection;
import persistence.WriterLog;
import utils.CodeRequest;
import utils.JSONUtils;
import utils.MessageRequest;
import utils.StringConstants;

import javax.mail.MessagingException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import static utils.JSONUtils.*;

public class LoginAssigment_Controller extends Thread {
    private Client loginClient;
    private final Socket socket;
    private final FamilyGroupManager model;
    private final WriterLog writerLog;
    private final DataInputStream input;
    private final DataOutputStream output;
    private boolean state;

    public LoginAssigment_Controller(Socket socket, FamilyGroupManager model, WriterLog writerLog) throws IOException {
        this.socket = socket;
        this.model = model;
        this.writerLog = writerLog;
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());
        state = true;
        this.start();
    }

    @Override
    public void run() {
        while (state) {
            try {
                if (input.available() > 0) {
                    String[] code = readData();
                    if (CodeRequest.REQUEST.equals(code[0])) {
                        readRequest(code[1]);
                    }
                }
                if (socket.isClosed())
                    state = false;
            } catch (IOException | MessagingException | IncorrectEmailException | UserAlreadyExistsException | EmailAlreadyRegisteredException | EmailNotRegisteredException e) {
                try {
                    output.writeUTF(JSONUtils.exceptionToJSON(CodeRequest.EXCEPTION, e.getMessage()));
                    writerLog.writeLog("["+ LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE).replace(':','_')+"] - " + e.getLocalizedMessage()+"\n");
                } catch (IOException ioException) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void readRequest(String s) throws IOException, UserAlreadyExistsException, IncorrectEmailException, MessagingException, EmailAlreadyRegisteredException, EmailNotRegisteredException {
        switch (s) {
            case MessageRequest.REQUEST_SIGN_IN_CODE:
                readCredentials();
                break;
            case MessageRequest.REQUEST_NEW_USER_CODE:
                createNewUser();
                break;
            case MessageRequest.REQUEST_CLOSE_CONEXION_CODE:
                socket.close();
                break;
            case MessageRequest.REQUEST_SEND_EMAIL_RECOVER_CODE:
                sendRecoverEmail();
                break;
        }
    }


    private void createNewUser() throws IOException, UserAlreadyExistsException, EmailAlreadyRegisteredException {
        output.writeUTF(model.isThereAdmin()
                ? requestToJSON(CodeRequest.REQUEST, MessageRequest.THERE_IS_ADMIN_CODE)
                : requestToJSON(CodeRequest.REQUEST, MessageRequest.THERE_ISNT_ADMIN_CODE));
        User newUser = userFromJSON(input.readUTF(), User.class);
        model.addUser(newUser);
        sendSucessfullMessage();
        readCredentials(newUser.getid());
    }

    private void sendSucessfullMessage() throws IOException {
        output.writeUTF(requestToJSON(CodeRequest.ADVICE, StringConstants.MESSAGE_USER_CREATED));
    }

    private void readCredentials(String getId) throws IOException {
        String[] credentials = getId.split("@");
        if (createClient(credentials[0], credentials[1])) state = false;
    }

    private String[] readData() throws IOException {
        String i = input.readUTF();
        return (String[]) objectFromJSON(i, String[].class);
    }

    private void readCredentials() throws IOException {
        String[] credentials = (String[]) objectFromJSON(input.readUTF(), String[].class);
        if (!createClient(credentials[0], credentials[1])) sendFailedLogin();
        else {
            state = false;
        }

    }

    private void sendFailedLogin() throws IOException {
        output.writeUTF(requestToJSON(CodeRequest.EXCEPTION, StringConstants.FAILED_LOGIN_INEXIST_USER_CODE));
    }

    private boolean createClient(String userName, String password) throws IOException {
        boolean result = true;
        User user = model.searchMember(userName + "@" + password);
        if (user == null) result = false;
        else loginClient = user.isAdmin()
                ? new AdminController(writerLog, new Connection(socket, writerLog), model, user)
                : new MemberController(writerLog, new Connection(socket, writerLog), user, model);
        if (result) sendAdviceClientLaunched(user);

        return result;
    }

    private void sendAdviceClientLaunched(User user) throws IOException {
        output.writeUTF(requestToJSON(CodeRequest.ADVICE, MessageRequest.ADVICE_CLIENT_LAUNCHED));
        if (user.isAdmin())
            output.writeUTF(requestToJSON(CodeRequest.ADVICE, StringConstants.ADMIN_TYPE));
        else
            output.writeUTF(requestToJSON(CodeRequest.ADVICE, StringConstants.MEMBER_TYPE));
    }

    private  void sendAdvicePasswordRecoverSended() throws IOException {
        output.writeUTF(requestToJSON(CodeRequest.ADVICE, MessageRequest.ADVICE_EMAIL_SENDED));
    }

    public Client getClient() {
        return loginClient;
    }

    private void sendRecoverEmail() throws IOException, MessagingException, EmailNotRegisteredException {
        String email = (String) JSONUtils.objectFromJSON(input.readUTF(), String.class);
        model.sendEmailRecover(email);
        sendAdvicePasswordRecoverSended();
    }
}
