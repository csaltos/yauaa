/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2016 Niels Basjes
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package nl.basjes.parse.useragent.servlet;

import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.Properties;

import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;

@Path("parse")
public class ParseService {

    private static final UserAgentAnalyzer USER_AGENT_ANALYZER = new UserAgentAnalyzer();

    private static String version   = "Unable to read version";
    private static String buildtime = "Unable to read version";

    static {
        Properties prop = new Properties();
        InputStream input = null;

        try {

            String filename = "git.properties";
            input = ParseService.class.getClassLoader().getResourceAsStream(filename);
            if (input == null) {
                System.out.println("Sorry, unable to find " + filename);
            }

            //load a properties file from class path, inside static method
            prop.load(input);

            //get the property value and print it out
            String gitVersion = prop.getProperty("git.commit.id.describe-short");
            if (gitVersion == null) {
                ParseService.version = "Undefined";
            } else {
                ParseService.version = gitVersion;
            }

            String gitBuildTime = prop.getProperty("git.build.time");
            if (gitBuildTime == null) {
                ParseService.buildtime = "Undefined";
            } else {
                ParseService.buildtime = gitBuildTime;
            }



        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    protected synchronized UserAgent parse(String userAgentString) {
        return USER_AGENT_ANALYZER.parse(userAgentString); // This class and method are NOT threadsafe/reentrant !
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response getHtml(@HeaderParam("User-Agent") String userAgentString) {
        return doHTML(userAgentString);
    }

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getHtmlPOST(String userAgentString) {
        return doJSon(userAgentString);
    }

    @GET
    @Path("/{UserAgent : .+}")
    @Produces(MediaType.TEXT_HTML)
    public Response getHtmlPath(@Context UriInfo uriInfo) throws UnsupportedEncodingException {
        // PathParams go wrong with ';' and '/' in it (MatrixParam parsing). So we just get the entire Path and extract the stuff ourselves
        return doHTML(getUseragentFromPath(uriInfo, "parse"));
    }

    private String getUseragentFromPath(UriInfo uriInfo, String pathPrefix) {
        try {
            return URLDecoder.decode(uriInfo.getPath().replaceAll("^"+pathPrefix+"/", ""), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // Never happens. UTF-8 is valid and hardcoded
            return "";
        }
    }

    @GET
    @Path("/json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJSon(@HeaderParam("User-Agent") String userAgentString) {
        return doJSon(userAgentString);
    }

    @POST
    @Path("/json")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJSonPOST(String userAgentString) {
        return doJSon(userAgentString);
    }

    @GET
    @Path("/json/{UserAgent : .+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJSonPath(@Context UriInfo uriInfo) {
        // PathParams go wrong with ';' and '/' in it (MatrixParam parsing). So we just get the entire Path and extract the stuff ourselves
        return doJSon(getUseragentFromPath(uriInfo, "parse/json"));
    }

    private Response doHTML(String userAgentString) {
        long start = System.nanoTime();

        if (userAgentString == null) {
            return Response
                .status(Response.Status.PRECONDITION_FAILED)
                .entity("<b><u>The User-Agent header is missing</u></b>")
                .build();
        }

        Response.ResponseBuilder responseBuilder = Response.status(200);

        StringBuilder sb = new StringBuilder(4096);
        try {
            sb.append("<!DOCTYPE html>");
            sb.append("<html><head profile=\"http://www.w3.org/2005/10/profile\">");
            sb.append("<link rel=\"icon\" type=\"image/ico\" href=\"/static/favicon.ico\" />\n");
            sb.append("<meta http-equiv='Content-Type' content='text/html; charset=UTF-8'>");
            sb.append("<title>Analyzing the useragent</title></head>");
            sb.append("<body>");

            sb.append("<h1>Analyzing your useragent string</h1>");

            sb.append("Version    : ").append(version).append("<br/>");
            sb.append("Build time : ").append(buildtime).append("<br/>");

            UserAgent userAgent = parse(userAgentString);

            sb.append("Received useragent header: <h2>").append(escapeHtml4(userAgent.getUserAgentString())).append("</h2>");
            sb.append("<table border=1>");
            sb.append("<tr><th>Field</th><th>Value</th></tr>");
            for (String fieldname : userAgent.getAvailableFieldNamesSorted()) {
                sb.append("<tr>").append(
                    "<td>").append(camelStretcher(escapeHtml4(fieldname))).append("</td>").append(
                    "<td>").append(escapeHtml4(userAgent.getValue(fieldname))).append("</td>").append(
                    "</tr>");
            }
            sb.append("</table>");

            sb.append("<ul>");
            sb.append("<li><a href=\"/\">HTML (from header)</a></li>");
            sb.append("<li><a href=\"/").append(escapeHtml4(userAgentString)).append("\">HTML (from url)</a></li>");
            sb.append("<li><a href=\"/json\">Json (from header)</a></li>");
            sb.append("<li><a href=\"/json/").append(escapeHtml4(userAgentString)).append("\">Json (from url)</a></li>");
            sb.append("</ul>");

        } finally {
            long stop = System.nanoTime();
            double milliseconds = (stop - start) / 1000000.0;

            sb.append("<br/>");
            sb.append("<u>Building this page took ").append(String.format(Locale.ENGLISH, "%3.3f", milliseconds)).append(" ms.</u><br/>");
            sb.append("</body>");
            sb.append("</html>");
        }
        return responseBuilder.entity(sb.toString()).build();
    }

    private String camelStretcher(String input) {
        String result = input.replaceAll("([A-Z])", " $1");
        result = result.replace("Device", "<b><u>Device</u></b>");
        result = result.replace("Operating System", "<b><u>Operating System</u></b>");
        result = result.replace("Layout Engine", "<b><u>Layout Engine</u></b>");
        result = result.replace("Agent", "<b><u>Agent</u></b>");
        return result;
    }

    private Response doJSon(String userAgentString) {
        if (userAgentString == null) {
            return Response
                .status(Response.Status.PRECONDITION_FAILED)
                .entity("{ error: \"The User-Agent header is missing\" }")
                .build();
        }
        Response.ResponseBuilder responseBuilder = Response.status(200);
        UserAgent userAgent = parse(userAgentString);
        return responseBuilder.entity(userAgent.toJson()).build();
    }


}