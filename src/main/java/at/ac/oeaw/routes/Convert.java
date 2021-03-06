package at.ac.oeaw.routes;

import at.ac.oeaw.elements.Viewable;
import at.ac.oeaw.elements.enhancements.EntityEnhancement;
import at.ac.oeaw.elements.enhancements.TextEnhancement;
import at.ac.oeaw.helpers.FileReader;
import at.ac.oeaw.helpers.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.servlet.ServletContext;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.StringTokenizer;

@Path("/convert")
public class Convert {
    private static final Logger logger = (Logger) LogManager.getLogger(Convert.class);

    //default
    private Double CONFIDENCE_THRESHOLD = 0.7;
    private ObjectMapper mapper = new ObjectMapper();

    @Context
    ServletContext servletContext;


    @POST
    @Path("/")
    @Consumes("application/x-www-form-urlencoded;charset=UTF-8")
    public Response convertPOST(@FormParam("input") String input, @FormParam("confidence") String confidence) {

        if (confidence != null) {
            try {
                CONFIDENCE_THRESHOLD = Double.parseDouble(confidence);
            } catch (NumberFormatException e) {
                //it means probably an empty string is provided, do nothing and leave the default value
            }

        }
        if (CONFIDENCE_THRESHOLD < 0.0 || CONFIDENCE_THRESHOLD > 1.0) {
            return Response.status(400).entity("Confidence(double) must be between 0 and 1").build();
        }


        boolean json;
        try {
            mapper.readTree(input);
            json = true;
        } catch (IOException e) {
            //this means input is regarded as plain text
            json = false;
        }


        Document doc;
        try {
            doc = Jsoup.parse(FileReader.readFile(this.servletContext.getRealPath("/WEB-INF/classes/view/html/view.html")));
        } catch (IOException e) {
            logger.error(e.getMessage());
            return Response.serverError().entity("Something went wrong.").build();
        }

        if (json) {
            return processStanbolJSONtoHTML(doc, null, input, -1);
        } else {
            return processTEXTtoHTML(doc, null, input);
        }


    }

    @GET
    @Path("/")
    public Response convertGET(@QueryParam("URL") String URL, @QueryParam("confidence") String confidence) {

        Response response;
        try {
            response = RequestHandler.get(URL, null);
        } catch (BadRequestException e) {
            return Response.status(400).entity(e.getMessage()).build();
        } catch (ProcessingException e) {
            return Response.status(504).entity("The request to the URL provided exceeded the timout: " + RequestHandler.TIMEOUT).build();
        }


        if (confidence != null) {
            try {
                CONFIDENCE_THRESHOLD = Double.parseDouble(confidence);
            } catch (NumberFormatException e) {
                //it means probably an empty string is provided, do nothing and leave the default value
            }

        }
        if (CONFIDENCE_THRESHOLD < 0.0 || CONFIDENCE_THRESHOLD > 1.0) {
            return Response.status(400).entity("Confidence(double) must be between 0 and 1").build();
        }


        Document doc;
        try {
            doc = Jsoup.parse(FileReader.readFile(this.servletContext.getRealPath("/WEB-INF/classes/view/html/view.html")));
        } catch (IOException e) {
            logger.error(e.getMessage());
            return Response.serverError().entity("Something went wrong.").build();
        }


        //Content-Type is built like this: Content-Type := type "/" subtype *[";" parameter]
        //so what interests us comes before ';'
        //sometimes there are no parameters but this split method should cover it
        String contentType = response.getHeaderString("Content-Type").split(";")[0];

        if (contentType == null) {
            throw new BadRequestException("The given URL: '" + URL + "' doesn't have a content-type field in its response headers. Distanbol expects either an text/plain for fulltext or an application/json for stanbol output as the Content-Type.");
        } else if (contentType.equals("application/json") || contentType.equals("application/ld+json")) {
            String json = response.readEntity(String.class);
            return processStanbolJSONtoHTML(doc, URL, json, -1);
        } else if (contentType.equals("text/plain")) {
            String fulltext = response.readEntity(String.class);
            return processTEXTtoHTML(doc, URL, fulltext);
        } else {
            return Response.status(400).entity("The given URL: '" + URL + "' doesn't point to a text, json or jsonld file. Distanbol expects either an text/plain for fulltext or an application/json for stanbol output as the Content-Type.").build();
        }


    }

    private Response processTEXTtoHTML(Document doc, String URL, String fulltext) {

        StringTokenizer st = new StringTokenizer(fulltext);
        int words = st.countTokens();

        try {
            String stanbolJson = RequestHandler.postToStanbol(fulltext);
            return processStanbolJSONtoHTML(doc, URL, stanbolJson, words);
        } catch (IOException e) {
            logger.error(e.getMessage());
            return Response.serverError().entity("Something went wrong.").build();
        }

    }


    private Response processStanbolJSONtoHTML(Document doc, String URL, String json, int wordCount) {
        boolean fromURL = true;
        if (URL == null) {
            fromURL = false;
        }

        JsonNode jsonNode;
        try {
            jsonNode = mapper.readTree(json);
        } catch (IOException e) {
            return Response.status(400).entity("Given json file is not valid.").build();
        }


        //there are two 3 divs in the template for two cases
        //if the input was a url, only a url form and the fulltext as just a text is displayed
        //if the input was text, text is displayed inside a textarea form
        if (fromURL) {
            Element urlInput = doc.getElementById("URLInput");
            urlInput.attr("value", URL);

            Element confidenceInputURL = doc.getElementById("confidenceURL");
            confidenceInputURL.attr("value", String.valueOf(CONFIDENCE_THRESHOLD));

            Element sourceHTML = doc.getElementById("source");
            sourceHTML.append("<b>Source URL: </b> <a href=\"" + URL + "\">" + URL + "</a>");

            Element textareaDiv = doc.getElementById("textForm");
            textareaDiv.attr("class", "hidden");
        } else {
            Element confidenceInputTEXT = doc.getElementById("confidenceTEXT");
            confidenceInputTEXT.attr("value", String.valueOf(CONFIDENCE_THRESHOLD));

            Element fulltextDiv = doc.getElementById("fulltext");
            fulltextDiv.attr("class", "hidden");
            Element urlDiv = doc.getElementById("urlForm");
            urlDiv.attr("class", "hidden");
        }


        if (jsonNode.isArray()) {
            Iterator<JsonNode> iterator = jsonNode.elements();


            ArrayList<Viewable> viewables = new ArrayList<>();
            ArrayList<EntityEnhancement> entityEnhancements = new ArrayList<>();
            ArrayList<TextEnhancement> textEnhancements = new ArrayList<>();

            //create an object for each node and save them into respective lists
            while (iterator.hasNext()) {
                JsonNode node = iterator.next();


                //this happens only once
                if (node.get("fulltext") != null) {

                    if (fromURL) {
                        Element fulltextHTML = doc.getElementById("fulltext");
                        String fulltext = node.get("fulltext").asText();

                        fulltextHTML.append(fulltext);
                    } else {
                        Element textInputHTML = doc.getElementById("textInput");
                        String fulltext = node.get("fulltext").asText();

                        textInputHTML.append(fulltext);
                    }

                } else {

                    //there are three types of nodes: viewables, entity enhancements and text enhancements.
                    //Viewables are entities to display.
                    //Entity enhancements contain confidence information
                    //Text enhancements contain context information.

                    ArrayNode typesNode = (ArrayNode) node.get("@type");
                    if (typesNode != null) {
                        if (typesNode.size() == 2 && typesNode.get(0).asText().equals("http://fise.iks-project.eu/ontology/Enhancement")) {

                            switch (typesNode.get(1).asText()) {
                                case "http://fise.iks-project.eu/ontology/TextAnnotation":
                                    TextEnhancement textEnhancement = new TextEnhancement(node);
                                    textEnhancements.add(textEnhancement);
                                    break;
                                case "http://fise.iks-project.eu/ontology/EntityAnnotation":
                                    EntityEnhancement entityEnhancement = new EntityEnhancement(node);
                                    //only take entity enhancements that are over the threshold,
                                    if (entityEnhancement.getConfidence() >= CONFIDENCE_THRESHOLD) {
                                        entityEnhancements.add(entityEnhancement);
                                    }
                                    break;
                                default:
                                    return Response.status(400).entity("The given Stanbol output is not valid.").build();
                            }

                        } else {
                            Viewable viewable = new Viewable(node);
                            viewables.add(viewable);
                        }

                    } else {
                        //node has no type, it means it is a viewable
                        Viewable viewable = new Viewable(node);
                        viewables.add(viewable);
                    }
                }

            }


            ArrayList<Viewable> finalViewables = new ArrayList<>();

            //Each viewable has exactly one matching entityEnhancement.
            //Each entityEnhancement can have one or more textEnhancements.

            //entityEnhancements list is already filtered above to take confidence higher than threshold
            for (Viewable viewable : viewables) {

                for (EntityEnhancement entityEnhancement : entityEnhancements) {

                    if (viewable.getId().equals(entityEnhancement.getReference())) {

                        viewable.setEntityEnhancement(entityEnhancement);
                        for (TextEnhancement textEnhancement : textEnhancements) {

                            if (entityEnhancement.getRelations().contains(textEnhancement.getId())) {
                                viewable.addTextEnhancement(textEnhancement);
                            }
                        }

                        if (!finalViewables.contains(viewable)) {
                            finalViewables.add(viewable);
                        }


                    }

                }
            }

            if (finalViewables.size() == 0) {
                return Response.status(400).entity("There are no entities above the given threshold: " + CONFIDENCE_THRESHOLD).build();
            } else {
                Element viewablesHTML = doc.getElementById("viewables");

                Element formHTML = doc.getElementById("tableBody");
                boolean firstElement = true;

                for (Viewable viewable : finalViewables) {

                    //to have a small space between elements
                    if (firstElement) {
                        firstElement = false;
                    } else {
                        viewablesHTML.append("<hr>");
                    }

                    try {
                        viewablesHTML.append(viewable.getHTMLDepiction(this.servletContext.getRealPath("/WEB-INF/classes/view/html/viewable/Viewable.html")));


                        formHTML.append(viewable.getHTMLTableRowDepiction());
                    } catch (IOException e) {
                        logger.error(e.getMessage());
                        return Response.serverError().entity("Something went wrong.").build();
                    }

                }

                //statistics
                Element statisticsHTML = doc.getElementById("statistics");
                if(wordCount>0){
                    statisticsHTML.append("<b>Word Count: </b>").append(String.valueOf(wordCount)).append("<br>");
                }
                statisticsHTML.append("<b>Total Number of Entities Found: </b>").append(String.valueOf(viewables.size())).append("<br>");
                statisticsHTML.append("<b>Number of Entities Above Confidence Threshold: </b>").append(String.valueOf(finalViewables.size())).append("<br>");

                return Response.accepted().entity(doc.html()).type("text/html").build();
            }


        } else {
            return Response.status(400).entity("The given Stanbol output is not valid.").build();
        }

    }


}
