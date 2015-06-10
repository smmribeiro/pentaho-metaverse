/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2015 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.metaverse.graph;

import com.tinkerpop.blueprints.Graph;
import org.pentaho.metaverse.api.IGraphWriter;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The GraphMLWriter class contains methods for writing a metaverse graph model in GraphML format
 * 
 */
public class GraphMLWriter implements IGraphWriter {

  @Override
  public void outputGraph( Graph graph, OutputStream graphMLOutputStream ) throws IOException {
    com.tinkerpop.blueprints.util.io.graphml.GraphMLWriter writer =
        new com.tinkerpop.blueprints.util.io.graphml.GraphMLWriter( graph );

    writer.setNormalize( true );
    writer.outputGraph( graphMLOutputStream );
    //    com.tinkerpop.blueprints.util.io.graphml.GraphMLWriter.outputGraph( graph, graphMLOutputStream );
  }

}
