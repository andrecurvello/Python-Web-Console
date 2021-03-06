package org.thomnichols.pythonwebconsole;

import static com.google.appengine.api.labs.taskqueue.TaskOptions.Builder.url;
import static org.thomnichols.pythonwebconsole.Util.validateCaptcha;
import static org.thomnichols.pythonwebconsole.Util.validateParam;

import java.io.IOException;
import java.util.List;

import javax.jdo.JDOObjectNotFoundException;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;
import javax.jdo.Query;
import javax.jdo.Transaction;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thomnichols.pythonwebconsole.model.Script;
import org.thomnichols.pythonwebconsole.model.Tag;

import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.labs.taskqueue.Queue;
import com.google.appengine.api.labs.taskqueue.QueueFactory;
import com.google.appengine.api.labs.taskqueue.TaskOptions.Method;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;

/**
 * This servlet handles sharing and retrieving saved scripts. 
 * TODO handle script update & delete by author?  
 * @author tnichols
 */
public class ScriptServlet extends HttpServlet {
	private static final long serialVersionUID = -2788426290753239213L;
	final Logger log = LoggerFactory.getLogger( getClass() );

	private PersistenceManagerFactory pmf;
	private String recapPrivateKey;
	private boolean debug = false;
	
	@Override
	public void init() throws ServletException {
		this.pmf = (PersistenceManagerFactory)super.getServletContext().getAttribute( "persistence" );
		this.recapPrivateKey = (String)getServletContext().getAttribute( "recaptchaPrivateKey" );
		this.debug = (Boolean)getServletContext().getAttribute( "debug" );
	}
	
	@Override
	protected void doGet( HttpServletRequest req, HttpServletResponse resp )
			throws ServletException, IOException {
		PersistenceManager pm = pmf.getPersistenceManager();
        try {
        	String permalink = getPermalink( req );
    		// lookup script based on script id/ permalink
        	Script script = pm.getObjectById( Script.class, permalink );
        	req.setAttribute( "script", script );
        	req.setAttribute( "comments", script.getComments() );
        	
        	UserService userSvc = UserServiceFactory.getUserService();
        	if ( userSvc.isUserLoggedIn() && userSvc.isUserAdmin() )
        		req.setAttribute( "admin", true );
        	
    		req.getRequestDispatcher( "/script.jsp" ).forward( req, resp );
        }
        catch ( PermalinkMissingException ex ) {
    		resp.sendError( 400, "Hrm, something is missing here." );
        }
        catch ( JDOObjectNotFoundException ex ) {
        	resp.sendError( 404, "Say wha?" );
        }
        finally { pm.close(); }
	}
	
	@Override
	protected void doPost( HttpServletRequest req, HttpServletResponse resp )
			throws ServletException, IOException {
		
		if ( "DELETE".equals( req.getParameter( "__method" ) ) ) {
			this.doDelete( req, resp );
			return;
		}
		
		PersistenceManager pm = pmf.getPersistenceManager();
		Transaction tx = pm.currentTransaction();
        try {
        	if ( ! debug ) validateCaptcha( recapPrivateKey, req );
        	String author = validateParam( req, "author" );
        	String source = validateParam( req, "source" );
        	String title = validateParam( req, "title" );

        	String tagString = req.getParameter( "tags" );
        	if ( tagString == null ) tagString = "";
        	String[] tags = tagString.split( "[\\s,]+" );
        	
        	Script script = new Script( author, source, title, tags );
        	tx.begin();
        	Query query = pm.newQuery( Script.class, "permalink == :p");
        	// handle conflicts if permalink already exists
        	while ( ((List<?>)query.execute( script.getPermalink() )).size() > 0 )
        		script.generateNewPermalink();
        	log.debug( "Saving new script with permalink: {}", script.getPermalink() );
        	pm.makePersistent( script );

        	this.ping(tx);
            tx.commit();

            // update tag count:
            for ( String tagName : script.getTags() ) {
	            try {
	    			Tag tag = pm.getObjectById(Tag.class, tagName);
	    			tag.setCount( tag.getCount() + 1 );
	    		}
	    		catch ( JDOObjectNotFoundException ex ) {
	    			Tag tag = new Tag(tagName, 1);
	    			pm.makePersistent(tag);
	    		}
            }

            // Send a 201:Created response for Ajax requests.
        	if ( "XMLHttpRequest".equals(req.getHeader("X-Requested-With")) ) {
        		resp.setStatus(201);
        		resp.setHeader( "Location", getServletContext().getContextPath() 
        				+ "/script/" + script.getPermalink() );
        		return;
        	}
	
            resp.sendRedirect( "/script/"+script.getPermalink() );
        }
        catch ( ValidationException ex ) {
        	log.warn( "Validation failed", ex );
			resp.sendError( 400, "Validation failed  " + ex.getMessage() );
        }
        catch ( Exception e ) {
        	log.warn( "Unexpected exception while saving script", e );
			resp.sendError( 500, "Unexpected error: " + e.getMessage() );
        } finally {
        	if ( tx.isActive() ) {
        		log.warn( "Rolling back tx!" );
        		tx.rollback();
        	}
        	pm.close();
        }
	}
	
	@Override
	protected void doDelete( HttpServletRequest req, HttpServletResponse resp )
			throws ServletException, IOException {
		UserService userSvc = UserServiceFactory.getUserService();
		if ( ! userSvc.isUserLoggedIn() || ! userSvc.isUserAdmin() ) {
			resp.sendError( 401, "No way!" );
			return; /* TODO I don't think it's possible to set a security 
					filter for a single action in web.xml */ 
		}
		PersistenceManager pm = this.pmf.getPersistenceManager();
		Transaction tx = pm.currentTransaction();
		try {
			String permalink = getPermalink( req );
			
			tx.begin();
			Script s = pm.getObjectById( Script.class, permalink );
			pm.deletePersistentAll( s.getComments() );
			pm.deletePersistent( s );
			tx.commit();
		}
		catch ( PermalinkMissingException ex ) {
    		resp.sendError( 400, "Hrm, something is missing here." );			
		}
		catch ( JDOObjectNotFoundException ex ) {
        	resp.sendError( 404, "Say wha?" );			
		}
		finally {
			if ( tx.isActive() ) {
        		log.warn( "Rolling back tx!" );
        		tx.rollback();
			}
			pm.close();
		}
	}
	
	/**
	 * Send sitemap ping for Google and Bing.  These tasks fire asynchronously
	 * so if there is a delayed response from one of these services, it does not
	 * hold up submission of a script.
	 */
	private void ping( Transaction tx ) {
		if ( debug ) return;
		Queue queue = QueueFactory.getQueue( "sitemap" );
		queue.add( DatastoreServiceFactory.getDatastoreService().getCurrentTransaction(), 
				url("/tasks/ping").method( Method.POST ).param( "engine", "google" ) );
		queue.add( DatastoreServiceFactory.getDatastoreService().getCurrentTransaction(), 
				url("/tasks/ping").method( Method.POST ).param( "engine", "bing" ) );
	}
	
	protected String getPermalink( HttpServletRequest req ) throws PermalinkMissingException {
		// get the permalink as the last part of the path
		String url = req.getRequestURI();
    	String permalink = url.substring( url.lastIndexOf( "/" )+1, url.length() );
    	if ( StringUtils.isBlank( permalink ) )
    		throw new PermalinkMissingException();
    	return permalink;
	}
}
