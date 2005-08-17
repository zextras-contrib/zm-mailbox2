package com.zimbra.cs.client.soap;

import org.dom4j.Element;
import org.dom4j.DocumentHelper;

import com.zimbra.cs.service.admin.AdminService;
import com.zimbra.cs.service.ServiceException;

public class LmcPingRequest extends LmcSoapRequest {

	protected Element getRequestXML() {
		Element request = DocumentHelper.createElement(AdminService.PING_REQUEST);
		return request;
	}
	
	protected LmcSoapResponse parseResponseXML(Element responseXML)
	    throws ServiceException 
    {
		// there is no response to the request, only a fault
		LmcPingResponse response = new LmcPingResponse();
		return response;
	}
}