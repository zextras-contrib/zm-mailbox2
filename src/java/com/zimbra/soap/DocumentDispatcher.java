/*
 * Created on May 26, 2004
 */
package com.zimbra.soap;

import java.util.HashMap;
import java.util.Map;

import org.dom4j.QName;

import com.zimbra.cs.service.Element;
import com.zimbra.cs.service.ServiceException;

/**
 * @author schemers
 */
public class DocumentDispatcher {

	Map mHandlers;
	
	public DocumentDispatcher() {
		mHandlers = new HashMap();
	}
	
	public void registerHandler(QName qname, DocumentHandler handler) {
		mHandlers.put(qname, handler);
	}
	
	public DocumentHandler getHandler(Element doc) {
		DocumentHandler handler = (DocumentHandler) mHandlers.get(doc.getQName());
		return handler;
	}

	public Element dispatch(Element doc, Map context) throws ServiceException {
		DocumentHandler handler = (DocumentHandler) mHandlers.get(doc.getQName());
		if (handler == null) 
			throw ServiceException.UNKNOWN_DOCUMENT(doc.getQualifiedName(), null);
		return handler.handle(doc, context);
	}
}
