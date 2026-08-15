package cn.tbforum.service;
public interface MailService {
 void sendPasswordReset(String email, String link);
}
