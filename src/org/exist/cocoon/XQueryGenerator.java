/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-03 Wolfgang M. Meier
 *  wolfgang@exist-db.org
 *  http://exist.sourceforge.net
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id$
 */
package org.exist.cocoon;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.avalon.framework.configuration.Configurable;
import org.apache.avalon.framework.configuration.Configuration;
import org.apache.avalon.framework.configuration.ConfigurationException;
import org.apache.avalon.framework.parameters.Parameters;
import org.apache.cocoon.ProcessingException;
import org.apache.cocoon.environment.Context;
import org.apache.cocoon.environment.ObjectModelHelper;
import org.apache.cocoon.environment.Request;
import org.apache.cocoon.environment.Response;
import org.apache.cocoon.environment.Session;
import org.apache.cocoon.environment.SourceResolver;
import org.apache.cocoon.environment.http.HttpEnvironment;
import org.apache.cocoon.generation.ServiceableGenerator;
import org.apache.excalibur.source.Source;
import org.exist.source.CocoonSource;
import org.exist.storage.serializers.EXistOutputKeys;
import org.exist.storage.serializers.Serializer;
import org.exist.xmldb.CollectionImpl;
import org.exist.xmldb.XQueryService;
import org.exist.xquery.functions.request.RequestModule;
import org.xml.sax.SAXException;
import org.xmldb.api.DatabaseManager;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Database;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.XMLResource;

/**
 * A generator for Cocoon which reads an XQuery script, executes it and passes
 * the results into the Cocoon pipeline.
 * 
 * The following optional attributes are accepted on the component declaration as default settings:
 * <li><tt>collection</tt>: identifies the XML:DB root collection used to process
 * the request</li>
 * <li><tt>user</tt></li>
 * <li><tt>password</tt></li>
 * <li><tt>create-session</tt>: if set to "true", indicates that an
 * HTTP session should be created upon the first invocation.</li>
 * <li><tt>expand-xincludes</tt></li>
 * 
 * See below an example declaration of the XQueryGenerator component with default values:
 * 
 * <map:generator logger="xmldb" name="xquery"
 * 		collection="xmldb:exist:///db/"
 * 		user="guest"
 * 		password="guest"
 *		create-session="false"
 * 		expand-xincludes="false"
 *		src="org.exist.cocoon.XQueryGenerator"/>
 * 
 * These settings can be overriden on a per-pipeline basis with sitemap parameters, see below with default values:
 * 
 * <pre>
 *  &lt;map:parameter name=&quot;collection&quot; value=&quot;xmldb:exist:///db&quot;/&gt;
 *  &lt;map:parameter name=&quot;user&quot; value=&quot;guest&quot;/&gt;
 *  &lt;map:parameter name=&quot;password&quot; value=&quot;guest&quot;/&gt;
 *  &lt;map:parameter name=&quot;create-session&quot; value=&quot;false&quot;/&gt;
 *  &lt;map:parameter name=&quot;expand-xincludes&quot; value=&quot;false&quot;/&gt;
 * </pre>
 * 
 * @author wolf
 */
public class XQueryGenerator extends ServiceableGenerator implements Configurable {
	public final static String DRIVER = "org.exist.xmldb.DatabaseImpl";

	private Source inputSource = null;
	private Map objectModel = null;

	private boolean createSession;
	private boolean defaultCreateSession = false;
	private final static String CREATE_SESSION = "create-session";

	private boolean expandXIncludes;
	private boolean defaultExpandXIncludes = false;
	private final static String EXPAND_XINCLUDES = "expand-xincludes";

	private String collectionURI;
	private String defaultCollectionURI = "xmldb:exist:///db";
	private final static String COLLECTION_URI = "collection";

	private String user;
	private String defaultUser = "guest";
	private final static String USER = "user";

	private String password;
	private String defaultPassword = "guest";
	private final static String PASSWORD = "password";

	private Map optionalParameters;

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.cocoon.generation.AbstractGenerator#setup(org.apache.cocoon.environment.SourceResolver,
	 *         java.util.Map, java.lang.String,
	 *         org.apache.avalon.framework.parameters.Parameters)
	 */
	public void setup(SourceResolver resolver, Map objectModel, String source,
						Parameters parameters) throws ProcessingException,
			SAXException, IOException {
		super.setup(resolver, objectModel, source, parameters);
		this.objectModel = objectModel;
		this.inputSource = resolver.resolveURI(source);
		this.collectionURI = parameters.getParameter(COLLECTION_URI,
				this.defaultCollectionURI);
		this.user = parameters.getParameter(USER, this.defaultUser);
		this.password = parameters.getParameter(PASSWORD, this.defaultPassword);
		this.createSession = parameters.getParameterAsBoolean(CREATE_SESSION,
				this.defaultCreateSession);
		this.expandXIncludes = parameters.getParameterAsBoolean(
				EXPAND_XINCLUDES, this.defaultExpandXIncludes);
		this.optionalParameters = new HashMap();
		String paramNames[] = parameters.getNames();
		for (int i = 0; i < paramNames.length; i++) {
			String param = paramNames[i];
			if (!(param.equals(COLLECTION_URI) || param.equals(USER)
					|| param.equals(PASSWORD)
					|| param.equals(CREATE_SESSION) || param
					.equals(EXPAND_XINCLUDES))) {
				this.optionalParameters.put(param, parameters
						.getParameter(param, ""));
			}
		}
		try {
			Class driver = Class.forName(DRIVER);
			Database database = (Database)driver.newInstance();
			DatabaseManager.registerDatabase(database);
		} catch(Exception e) {
			throw new ProcessingException("Failed to initialize database driver: " + e.getMessage(), e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.cocoon.generation.AbstractGenerator#recycle()
	 */
	public void recycle() {
		if (resolver != null)
			resolver.release(inputSource);
		inputSource = null;
		super.recycle();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.cocoon.generation.Generator#generate()
	 */
	public void generate() throws IOException, SAXException,
			ProcessingException {
		if (inputSource == null)
			throw new ProcessingException("No input source");
		Request request = ObjectModelHelper.getRequest(objectModel);
		Response response = ObjectModelHelper.getResponse(objectModel);
		Context context = ObjectModelHelper.getContext(objectModel);
		Session session = request.getSession(createSession);
		
		final String servletPath = request.getServletPath();
		final String pathInfo = request.getPathInfo();
		StringBuffer baseURIBuffer = new StringBuffer(servletPath);
		if (pathInfo != null) baseURIBuffer.append(pathInfo);
		int p = baseURIBuffer.lastIndexOf("/");
		if (p > -1)  baseURIBuffer.delete(p,baseURIBuffer.length());            
		final String baseURI = context.getRealPath(baseURIBuffer.toString());
		
		String user = null;
		String password = null;
		// check if user and password can be read from the session
		if (session != null && request.isRequestedSessionIdValid()) {
		    Object userObj = session.getAttribute("user");
		    Object passObj = session.getAttribute("password");
			user = userObj == null ? null : String.valueOf(userObj);
			password = passObj == null ? null : String.valueOf(passObj);
		}
		if (user == null)
			user = defaultUser;
		if (password == null)
			password = defaultPassword;
		try {
			Collection collection = DatabaseManager.getCollection(
					collectionURI, user, password);
			if (collection == null) {
				if (getLogger().isErrorEnabled())
					getLogger().error(
							"Collection " + collectionURI + " not found");
				throw new ProcessingException("Collection " + collectionURI
						+ " not found");
			}
			XQueryService service = (XQueryService) collection.getService(
					"XQueryService", "1.0");
			service.setProperty(Serializer.GENERATE_DOC_EVENTS, "false");
			service.setProperty(EXistOutputKeys.EXPAND_XINCLUDES,
					expandXIncludes ? "yes" : "no");
			service.setProperty("base-uri", baseURI);
			service.setNamespace(RequestModule.PREFIX, RequestModule.NAMESPACE_URI);
			service.setModuleLoadPath(baseURI);
			if(!((CollectionImpl)collection).isRemoteCollection()) {
				HttpServletRequest httpRequest = (HttpServletRequest) objectModel
						.get(HttpEnvironment.HTTP_REQUEST_OBJECT);
				service.declareVariable(RequestModule.PREFIX + ":request",
						new CocoonRequestWrapper(request, httpRequest));
				service.declareVariable(RequestModule.PREFIX + ":response",
						new CocoonResponseWrapper(response));
				if(session != null)
					service.declareVariable(RequestModule.PREFIX + ":session",
						new CocoonSessionWrapper(session));
			}
			declareParameters(service);
			
			String uri = inputSource.getURI();
			ResourceSet result = service.execute(new CocoonSource(inputSource));
			XMLResource resource;
			this.contentHandler.startDocument();
			for (long i = 0; i < result.getSize(); i++) {
				resource = (XMLResource) result.getResource(i);
				resource.getContentAsSAX(this.contentHandler);
			}
			this.contentHandler.endDocument();
		} catch (XMLDBException e) {
			throw new ProcessingException("XMLDBException occurred: "
					+ e.getMessage(), e);
		}
	}

	private void declareParameters(XQueryService service) throws XMLDBException {
		for(Iterator i = optionalParameters.entrySet().iterator(); i.hasNext(); ) {
			Map.Entry entry = (Map.Entry)i.next();
			service.declareVariable((String)entry.getKey(), entry.getValue());
		}
	}

	/**
	 * @see org.apache.avalon.framework.configuration.Configurable#configure(org.apache.avalon.framework.configuration.Configuration)
	 */
	public void configure(Configuration config) throws ConfigurationException {
		this.defaultCollectionURI = config.getAttribute(COLLECTION_URI, this.defaultCollectionURI);
		this.defaultCreateSession = config.getAttributeAsBoolean(CREATE_SESSION, this.defaultCreateSession);
		this.defaultExpandXIncludes = config.getAttributeAsBoolean(EXPAND_XINCLUDES, this.defaultExpandXIncludes);
		this.defaultPassword = config.getAttribute(PASSWORD, this.defaultPassword);
		this.defaultUser = config.getAttribute(USER, this.defaultUser);
	}
}
