package com.zimbra.soap.mail.message;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

import com.zimbra.common.soap.MailConstants;

/**
 * @zm-api-command-auth-required true
 * @zm-api-command-admin-auth-required false
 * @zm-api-command-description Invokes Mailbox::recordImapSession for the specified folder
 */
@XmlRootElement(name=MailConstants.E_RECORD_IMAP_SESSION_REQUEST)
public class RecordIMAPSessionRequest {

    /**
     * @zm-api-field-description The ID of the folder to record
     */
    @XmlAttribute(name=MailConstants.A_ID /* id */, required=true)
    private String folderId;

    public RecordIMAPSessionRequest() {}

    public RecordIMAPSessionRequest(String folderId) {
        setFolderId(folderId);
    }

    public String getFolderId() { return folderId; }

    public void setFolderId(String id) { this.folderId = id; }

}
