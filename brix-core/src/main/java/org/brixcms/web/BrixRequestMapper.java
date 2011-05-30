package org.brixcms.web;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.apache.wicket.Application;
import org.apache.wicket.MetaDataKey;
import org.apache.wicket.request.IRequestHandler;
import org.apache.wicket.request.IRequestMapper;
import org.apache.wicket.request.Request;
import org.apache.wicket.request.Url;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.http.WebRequest;
import org.apache.wicket.request.http.WebResponse;
import org.apache.wicket.util.string.Strings;
import org.brixcms.Brix;
import org.brixcms.BrixNodeModel;
import org.brixcms.Path;
import org.brixcms.config.BrixConfig;
import org.brixcms.jcr.api.JcrSession;
import org.brixcms.jcr.exception.JcrException;
import org.brixcms.jcr.wrapper.BrixNode;
import org.brixcms.plugin.site.SitePlugin;
import org.brixcms.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrixRequestMapper implements IRequestMapper {

	private static final Logger logger = LoggerFactory.getLogger(BrixRequestMapper.class);

	public static final String WORKSPACE_PARAM = Brix.NS_PREFIX + "workspace";

	private static final String COOKIE_NAME = "brix-revision";

	private static final MetaDataKey<String> WORKSPACE_METADATA = new MetaDataKey<String>() {
		private static final long serialVersionUID = 1L;
	};
	final Brix brix;
	private boolean handleHomePage = true;

	public BrixRequestMapper(Brix brix) {
		this.brix = brix;
	}

	@Override
	public IRequestHandler mapRequest(Request request) {

		final Url url = request.getClientUrl();

		// TODO: This is just a quick fix
		if (url.getSegments().size() > 0) {
			if (url.getSegments().get(0).equals("webdav") || url.getSegments().get(0).equals("jcrwebdav")) {
				return null;
			}
		}

		Path path = new Path("/" + url.toString());

		IRequestHandler handler = null;
		try {
			while (handler == null) {
				final BrixNode node = getNodeForUriPath(path);
				if (node != null) {
					handler = SitePlugin.get().getNodePluginForNode(node).respond(new BrixNodeModel(node), request.getRequestParameters());
				}
				if (handler != null || path.isRoot() || path.toString().equals(".")) {
					break;
				}
				path = path.parent();
			}
		} catch (JcrException e) {
			logger.warn("JcrException caught due to incorrect url", e);
		}

		return handler;
	}

	@Override
	public int getCompatibilityScore(Request request) {
		Url url = request.getUrl();
		if (url.getSegments().size() > 0) {
			if (url.getSegments().get(0).equals((Application.get().getMapperContext().getNamespace()))) {
				// starts with wicket namespace - is an internal wicket url
				return 0;
			}
		}
		// bluff we can parse all segments - makes sure we run first
		return request.getUrl().getSegments().size();
	}

	@Override
	public Url mapHandler(IRequestHandler requestHandler) {
		// this mapper does not support generating urls - they are generated
		// based on the directory structure
		return null;
	}

	public String getWorkspace() {
		String workspace = getWorkspaceFromUrl();

		if (workspace != null) {
			return workspace;
		}

		RequestCycle rc = RequestCycle.get();
		workspace = rc.getMetaData(WORKSPACE_METADATA);
		if (workspace == null) {
			WebRequest req = (WebRequest) RequestCycle.get().getRequest();
			WebResponse resp = (WebResponse) RequestCycle.get().getResponse();
			Cookie cookie = req.getCookie(COOKIE_NAME);
			workspace = getDefaultWorkspaceName();
			if (cookie != null) {
				if (cookie.getValue() != null)
					workspace = cookie.getValue();
			}
			if (!checkSession(workspace)) {
				workspace = getDefaultWorkspaceName();
			}
			if (workspace == null) {
				throw new IllegalStateException("Could not resolve jcr workspace to use for this request");
			}
			Cookie c = new Cookie(COOKIE_NAME, workspace);
			c.setPath("/");
			if (workspace.toString().equals(getDefaultWorkspaceName()) == false)
				resp.addCookie(c);
			else if (cookie != null)
				resp.clearCookie(cookie);
			rc.setMetaData(WORKSPACE_METADATA, workspace);
		}
		return workspace;
	}

	private String getWorkspaceFromUrl() {
		HttpServletRequest request = (HttpServletRequest) ((WebRequest) RequestCycle.get().getRequest()).getContainerRequest();

		if (request.getParameter(WORKSPACE_PARAM) != null) {
			return request.getParameter(WORKSPACE_PARAM);
		}

		String referer = request.getHeader("referer");

		if (!Strings.isEmpty(referer)) {
			return extractWorkspaceFromReferer(referer);
		} else {
			return null;
		}
	}

	private static String extractWorkspaceFromReferer(String refererURL) {
		int i = refererURL.indexOf('?');
		if (i != -1 && i != refererURL.length() - 1) {
			String param = refererURL.substring(i + 1);
			String params[] = Strings.split(param, '&');
			for (String s : params) {
				try {
					s = URLDecoder.decode(s, "utf-8");
				} catch (UnsupportedEncodingException e) {
					// rrright
					throw new RuntimeException(e);
				}
				if (s.startsWith(WORKSPACE_PARAM + "=")) {
					String value = s.substring(WORKSPACE_PARAM.length() + 1);
					if (value.length() > 0) {
						return value;
					}
				}
			}
		}
		return null;
	}

	private boolean checkSession(String workspaceId) {
		return brix.getWorkspaceManager().workspaceExists(workspaceId);
	}

	private String getDefaultWorkspaceName() {
		final Workspace workspace = brix.getConfig().getMapper().getWorkspaceForRequest(RequestCycle.get(), brix);
		return (workspace != null) ? workspace.getId() : null;
	}

	/**
	 * Resolves uri path to a {@link BrixNode}. By default this method uses
	 * {@link BrixConfig#getMapper()} to map the uri to a node path.
	 * 
	 * @param uriPath
	 *            uri path
	 * @return node that maps to the <code>uriPath</code> or <code>null</code>
	 *         if none
	 */
	public BrixNode getNodeForUriPath(final Path uriPath) {
		BrixNode node = null;

		// create desired nodepath
		final Path nodePath = brix.getConfig().getMapper().getNodePathForUriPath(uriPath.toAbsolute(), brix);

		if (nodePath != null) {
			// allow site plugin to translate the node path into an actual jcr
			// path
			final String jcrPath = SitePlugin.get().toRealWebNodePath(nodePath.toString());

			// retrieve jcr session
			final String workspace = getWorkspace();
			final JcrSession session = brix.getCurrentSession(workspace);

			if (session.itemExists(jcrPath)) {
				// node exists, return it
				node = (BrixNode) session.getItem(jcrPath);
			}
		}

		return node;
	}

	/**
	 * Creates a uri path for the specified <code>node</code> By default this
	 * method uses {@link BrixConfig#getMapper()} to map node path to a uri
	 * path.
	 * 
	 * @param node
	 *            node to create uri path for
	 * @return uri path that represents the node
	 */
	public Path getUriPathForNode(final BrixNode node) {
		// allow site plugin to translate jcr path into node path
		final String jcrPath = SitePlugin.get().fromRealWebNodePath(node.getPath());
		final Path nodePath = new Path(jcrPath);

		// use urimapper to create the uri
		return brix.getConfig().getMapper().getUriPathForNode(nodePath, brix);
	}
}
