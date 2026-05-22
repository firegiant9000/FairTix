package com.fairtix.notifications.application;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmailTemplateService {

    public String buildVerificationEmail(String name, String verificationLink) {
        String safeName = htmlEscape(name);
        String safeLink = htmlEscape(verificationLink);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Verify your FairTix account</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p>Click the link below to verify your email address. This link expires in 24 hours.</p>"
                + "<p><a href=\"" + safeLink + "\">" + safeLink + "</a></p>"
                + "<p>If you did not create a FairTix account, you can safely ignore this email.</p>"
                + "</body></html>";
    }

    public String buildPasswordResetEmail(String name, String resetLink) {
        String safeName = htmlEscape(name);
        String safeLink = htmlEscape(resetLink);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Reset your FairTix password</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p>Click the link below to reset your password. This link expires in 1 hour.</p>"
                + "<p><a href=\"" + safeLink + "\">" + safeLink + "</a></p>"
                + "<p>If you did not request a password reset, you can safely ignore this email.</p>"
                + "</body></html>";
    }

    public String buildOrderConfirmationEmail(String userName, String orderId, String eventTitle,
                                              String venueName, String eventDate,
                                              List<String> seats, String totalPrice) {
        String safeName = htmlEscape(userName);
        String safeOrderId = htmlEscape(orderId);
        String safeEvent = htmlEscape(eventTitle);
        String safeVenue = htmlEscape(venueName);
        String safeDate = htmlEscape(eventDate);
        String safeTotal = htmlEscape(totalPrice);

        StringBuilder seatRows = new StringBuilder();
        for (String seat : seats) {
            seatRows.append("<li>").append(htmlEscape(seat)).append("</li>");
        }

        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Your FairTix Order is Confirmed!</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p>Your order has been placed successfully. Here are your details:</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:500px;\">"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Order ID:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeOrderId + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Event:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeEvent + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Venue:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeVenue + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Date:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeDate + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Total:</strong></td>"
                + "<td style=\"padding:4px 8px;\"><strong>" + safeTotal + "</strong></td></tr>"
                + "</table>"
                + "<p><strong>Seats:</strong></p>"
                + "<ul>" + seatRows + "</ul>"
                + "<p><a href=\"/my-tickets\">View My Tickets</a></p>"
                + "<p style=\"color:#888;font-size:0.9em;\">Tickets are non-refundable unless the event is cancelled.</p>"
                + "</body></html>";
    }

    public String buildHoldExpiryEmail(String userName, String eventTitle,
                                       List<String> seats, String holdId) {
        String safeName = htmlEscape(userName);
        String safeEvent = htmlEscape(eventTitle);
        String safeHoldId = htmlEscape(holdId);

        StringBuilder seatRows = new StringBuilder();
        for (String seat : seats) {
            seatRows.append("<li>").append(htmlEscape(seat)).append("</li>");
        }

        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Your held seats have been released</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p>Your seat hold (ID: " + safeHoldId + ") for <strong>" + safeEvent
                + "</strong> has expired and the following seats have been released:</p>"
                + "<ul>" + seatRows + "</ul>"
                + "<p>Seat holds expire after 10 minutes. To secure your seats, complete checkout before the timer runs out.</p>"
                + "<p><a href=\"/events\">Browse events and try again</a></p>"
                + "</body></html>";
    }

    public String buildTransferRequestEmail(String recipientName, String senderEmail,
                                            String eventTitle, String seat, String acceptUrl) {
        String safeName = htmlEscape(recipientName);
        String safeSender = htmlEscape(senderEmail);
        String safeEvent = htmlEscape(eventTitle);
        String safeSeat = htmlEscape(seat);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>You have a ticket transfer request on FairTix</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p><strong>" + safeSender + "</strong> wants to transfer their ticket to you.</p>"
                + "<p><strong>Event:</strong> " + safeEvent + "<br>"
                + "<strong>Seat:</strong> " + safeSeat + "</p>"
                + "<p>Log in to FairTix and visit <em>My Tickets &rarr; Transfer Requests</em> to accept or reject this request. "
                + "This request expires in 7 days.</p>"
                + "</body></html>";
    }

    public String buildTransferAcceptedEmail(String senderName, String recipientEmail,
                                             String eventTitle, String seat) {
        String safeName = htmlEscape(senderName);
        String safeRecipient = htmlEscape(recipientEmail);
        String safeEvent = htmlEscape(eventTitle);
        String safeSeat = htmlEscape(seat);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Your ticket transfer was accepted</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p><strong>" + safeRecipient + "</strong> accepted your transfer for:</p>"
                + "<p><strong>Event:</strong> " + safeEvent + "<br>"
                + "<strong>Seat:</strong> " + safeSeat + "</p>"
                + "</body></html>";
    }

    public String buildTransferRejectedEmail(String senderName, String recipientEmail,
                                             String eventTitle, String seat) {
        String safeName = htmlEscape(senderName);
        String safeRecipient = htmlEscape(recipientEmail);
        String safeEvent = htmlEscape(eventTitle);
        String safeSeat = htmlEscape(seat);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Your ticket transfer was declined</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p><strong>" + safeRecipient + "</strong> declined your transfer for:</p>"
                + "<p><strong>Event:</strong> " + safeEvent + "<br>"
                + "<strong>Seat:</strong> " + safeSeat + "</p>"
                + "<p>Your ticket is still valid and has been returned to your account.</p>"
                + "</body></html>";
    }

    public String buildTransferExpiredEmail(String senderName, String eventTitle, String seat) {
        String safeName = htmlEscape(senderName);
        String safeEvent = htmlEscape(eventTitle);
        String safeSeat = htmlEscape(seat);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Your ticket transfer request has expired</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p>Your transfer request for the following ticket was not accepted in time:</p>"
                + "<p><strong>Event:</strong> " + safeEvent + "<br>"
                + "<strong>Seat:</strong> " + safeSeat + "</p>"
                + "<p>Your ticket is still valid and remains in your account.</p>"
                + "</body></html>";
    }

    public String buildRefundRequestedEmail(String userName, String orderId, String amount, String reason) {
        String safeName = htmlEscape(userName);
        String safeOrderId = htmlEscape(orderId);
        String safeAmount = htmlEscape(amount);
        String safeReason = htmlEscape(reason);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Refund Request Received</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p>We've received your refund request for order <strong>" + safeOrderId + "</strong>.</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:500px;\">"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Order ID:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeOrderId + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Refund Amount:</strong></td>"
                + "<td style=\"padding:4px 8px;\">$" + safeAmount + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Reason:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeReason + "</td></tr>"
                + "</table>"
                + "<p>Our team will review your request and process it within 3–5 business days. "
                + "You will receive an email once a decision has been made.</p>"
                + "<p><a href=\"/refunds\">View your refund requests</a></p>"
                + "</body></html>";
    }

    public String buildRefundInitiatedEmail(String userName, String orderId, String amount) {
        String safeName = htmlEscape(userName);
        String safeOrderId = htmlEscape(orderId);
        String safeAmount = htmlEscape(amount);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Your Refund Has Been Initiated</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p>Your refund for order <strong>" + safeOrderId + "</strong> has been approved and sent to our payment processor.</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:500px;\">"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Order ID:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeOrderId + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Refund Amount:</strong></td>"
                + "<td style=\"padding:4px 8px;\"><strong>$" + safeAmount + "</strong></td></tr>"
                + "</table>"
                + "<p>Stripe is now processing the refund. You will receive a second email once the funds have been returned to your original payment method, typically within 5–10 business days.</p>"
                + "<p><a href=\"/refunds\">View your refund requests</a></p>"
                + "</body></html>";
    }

    public String buildRefundCompletedEmail(String userName, String orderId, String amount) {
        String safeName = htmlEscape(userName);
        String safeOrderId = htmlEscape(orderId);
        String safeAmount = htmlEscape(amount);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Your Refund Has Been Processed</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p>Great news — your refund for order <strong>" + safeOrderId + "</strong> has been approved and processed.</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:500px;\">"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Order ID:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeOrderId + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Refund Amount:</strong></td>"
                + "<td style=\"padding:4px 8px;\"><strong>$" + safeAmount + "</strong></td></tr>"
                + "</table>"
                + "<p>Please allow 5–10 business days for the funds to appear in your account.</p>"
                + "<p><a href=\"/events\">Browse upcoming events</a></p>"
                + "</body></html>";
    }

    public String buildRefundRejectedEmail(String userName, String orderId, String reason, String adminNotes) {
        String safeName = htmlEscape(userName);
        String safeOrderId = htmlEscape(orderId);
        String safeReason = htmlEscape(reason);
        String safeNotes = htmlEscape(adminNotes != null ? adminNotes : "No additional details provided.");
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Update on Your Refund Request</h2>"
                + "<p>Hi " + safeName + ",</p>"
                + "<p>Unfortunately, your refund request for order <strong>" + safeOrderId + "</strong> has been reviewed and could not be approved.</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:500px;\">"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Order ID:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeOrderId + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Your Reason:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeReason + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Decision Notes:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeNotes + "</td></tr>"
                + "</table>"
                + "<p>If you believe this decision is incorrect, please contact our support team.</p>"
                + "</body></html>";
    }

    public String buildTicketCreatedEmail(String userEmail, String ticketId, String subject) {
        String safeEmail = htmlEscape(userEmail);
        String safeTicketId = htmlEscape(ticketId);
        String safeSubject = htmlEscape(subject);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Support Ticket Received</h2>"
                + "<p>Hi " + safeEmail + ",</p>"
                + "<p>We've received your support request and will respond as soon as possible.</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:500px;\">"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Ticket ID:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeTicketId + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Subject:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeSubject + "</td></tr>"
                + "</table>"
                + "<p><a href=\"/support/tickets/" + safeTicketId + "\">View your ticket</a></p>"
                + "</body></html>";
    }

    public String buildTicketReplyEmail(String userEmail, String ticketId, String subject, String replyMessage) {
        String safeEmail = htmlEscape(userEmail);
        String safeTicketId = htmlEscape(ticketId);
        String safeSubject = htmlEscape(subject);
        String safeMessage = htmlEscape(replyMessage);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>New Reply on Your Support Ticket</h2>"
                + "<p>Hi " + safeEmail + ",</p>"
                + "<p>A member of the FairTix support team has replied to your ticket.</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:500px;\">"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Ticket ID:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeTicketId + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Subject:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeSubject + "</td></tr>"
                + "</table>"
                + "<p><strong>Reply:</strong></p>"
                + "<blockquote style=\"border-left:3px solid #ccc;padding-left:12px;color:#555;\">"
                + safeMessage + "</blockquote>"
                + "<p><a href=\"/support/tickets/" + safeTicketId + "\">View full conversation</a></p>"
                + "</body></html>";
    }

    public String buildTicketClosedEmail(String userEmail, String ticketId, String subject) {
        String safeEmail = htmlEscape(userEmail);
        String safeTicketId = htmlEscape(ticketId);
        String safeSubject = htmlEscape(subject);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Support Ticket Closed</h2>"
                + "<p>Hi " + safeEmail + ",</p>"
                + "<p>Your support ticket has been closed.</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:500px;\">"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Ticket ID:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeTicketId + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Subject:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeSubject + "</td></tr>"
                + "</table>"
                + "<p>If your issue is not resolved, you can open a new ticket from the "
                + "<a href=\"/support\">support page</a>.</p>"
                + "</body></html>";
    }

    public String buildEventCancelledEmail(String userEmail, String eventTitle, String eventDate) {
        String safeEmail = htmlEscape(userEmail);
        String safeEvent = htmlEscape(eventTitle);
        String safeDate = htmlEscape(eventDate);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>Event Cancelled: " + safeEvent + "</h2>"
                + "<p>Hi " + safeEmail + ",</p>"
                + "<p>We're sorry to let you know that the following event has been cancelled:</p>"
                + "<table style=\"border-collapse:collapse;width:100%;max-width:500px;\">"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Event:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeEvent + "</td></tr>"
                + "<tr><td style=\"padding:4px 8px;\"><strong>Date:</strong></td>"
                + "<td style=\"padding:4px 8px;\">" + safeDate + "</td></tr>"
                + "</table>"
                + "<p>Your tickets have been cancelled and a full refund will be processed automatically. "
                + "Please allow 5–10 business days for the funds to appear in your account.</p>"
                + "<p><a href=\"/refunds\">View your refund status</a></p>"
                + "</body></html>";
    }

    public String buildQueueAdmittedEmail(String userEmail, String eventTitle, String expiresAt) {
        String safeEmail = htmlEscape(userEmail);
        String safeEvent = htmlEscape(eventTitle);
        String safeExpiry = htmlEscape(expiresAt);
        return "<html><body style=\"font-family:sans-serif;\">"
                + "<h2>You've been admitted — " + safeEvent + "</h2>"
                + "<p>Hi " + safeEmail + ",</p>"
                + "<p>Good news! You've been admitted from the queue for <strong>" + safeEvent + "</strong>.</p>"
                + "<p>Your admission window is open until <strong>" + safeExpiry + "</strong>. "
                + "Complete your seat selection and checkout before then to secure your tickets.</p>"
                + "<p><a href=\"/events\" style=\"background:#4CAF50;color:#fff;padding:10px 20px;"
                + "text-decoration:none;border-radius:4px;\">Go to Event</a></p>"
                + "<p style=\"color:#888;font-size:0.9em;\">If you no longer wish to attend, you can leave the queue from your account.</p>"
                + "</body></html>";
    }

    private String htmlEscape(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
