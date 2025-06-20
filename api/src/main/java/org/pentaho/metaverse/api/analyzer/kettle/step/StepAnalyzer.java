/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.metaverse.api.analyzer.kettle.step;

import com.google.common.base.Joiner;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Vertex;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.pentaho.di.core.ProgressNullMonitorListener;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStepMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.dictionary.DictionaryConst;
import org.pentaho.metaverse.api.IAnalysisContext;
import org.pentaho.metaverse.api.IClonableDocumentAnalyzer;
import org.pentaho.metaverse.api.IComponentDescriptor;
import org.pentaho.metaverse.api.IConnectionAnalyzer;
import org.pentaho.metaverse.api.IMetaverseNode;
import org.pentaho.metaverse.api.IMetaverseObjectFactory;
import org.pentaho.metaverse.api.INamespace;
import org.pentaho.metaverse.api.MetaverseAnalyzerException;
import org.pentaho.metaverse.api.MetaverseComponentDescriptor;
import org.pentaho.metaverse.api.Namespace;
import org.pentaho.metaverse.api.StepField;
import org.pentaho.metaverse.api.analyzer.kettle.BaseKettleMetaverseComponent;
import org.pentaho.metaverse.api.analyzer.kettle.ComponentDerivationRecord;
import org.pentaho.metaverse.api.analyzer.kettle.KettleAnalyzerUtil;
import org.pentaho.metaverse.api.messages.Messages;
import org.pentaho.metaverse.api.model.kettle.IFieldMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public abstract class StepAnalyzer<T extends BaseStepMeta> extends BaseKettleMetaverseComponent implements
  IClonableStepAnalyzer<T>, IFieldLineageMetadataProvider<T> {

  private static final Logger LOGGER = LogManager.getLogger( StepAnalyzer.class );
  public static final String NONE = "_none_";

  protected IClonableDocumentAnalyzer documentAnalyzer;
  protected IComponentDescriptor documentDescriptor;
  protected String documentPath;

  protected IComponentDescriptor descriptor;
  private StepNodes inputs;
  private StepNodes outputs;
  protected String[] prevStepNames = null;

  /**
   * A reference to the step under analysis
   */
  protected T baseStepMeta = null;

  /**
   * The step's parent StepMeta object (to get the parent TransMeta, in/out fields, etc.)
   */
  protected StepMeta parentStepMeta = null;

  /**
   * A reference to the transformation that contains the step under analysis
   */
  protected TransMeta parentTransMeta = null;

  /**
   * A reference to the root node created by the analyzer (usually corresponds to the step under analysis)
   */
  protected IMetaverseNode rootNode = null;

  /**
   * A reference to a connection analyzer
   */
  protected IConnectionAnalyzer connectionAnalyzer = null;

  /**
   * The stream fields coming into the step
   */
  protected Map<String, RowMetaInterface> prevFields = null;

  /**
   * The stream fields coming out of the step
   */
  protected RowMetaInterface stepFields = null;

  @Override
  public IMetaverseNode analyze( IComponentDescriptor descriptor, T meta ) throws MetaverseAnalyzerException {

    LOGGER.info( Messages.getString( "INFO.runningAnalyzer", meta.getParentStepMeta().getParentTransMeta().getName(),
      meta.getParentStepMeta().getName() ) );

    setDescriptor( descriptor );
    baseStepMeta = meta;

    validateState( descriptor, meta );

    // Add yourself
    rootNode = createNodeFromDescriptor( descriptor );
    String stepType = null;
    try {
      stepType =
        PluginRegistry.getInstance().findPluginWithId( StepPluginType.class, parentStepMeta.getStepID() ).getName();
    } catch ( Throwable t ) {
      stepType = parentStepMeta.getStepID();
    }
    rootNode.setProperty( DictionaryConst.PROPERTY_PLUGIN_ID, parentStepMeta.getStepID() );
    rootNode.setProperty( DictionaryConst.PROPERTY_STEP_TYPE, stepType );
    rootNode.setProperty( DictionaryConst.PROPERTY_COPIES, meta.getParentStepMeta().getCopies() );
    rootNode.setProperty( DictionaryConst.PROPERTY_ANALYZER, this.getClass().getSimpleName() );
    rootNode.setProperty( DictionaryConst.PROPERTY_DESCRIPTION, parentStepMeta.getDescription() );
    metaverseBuilder.addNode( rootNode );

    inputs = processInputs( meta );
    outputs = processOutputs( meta );

    Set<StepField> usedFields = getUsedFields( meta );
    if ( CollectionUtils.isNotEmpty( usedFields ) ) {
      processUsedFields( usedFields );
    }

    Set<ComponentDerivationRecord> changes = getChanges();
    for ( ComponentDerivationRecord change : changes ) {
      mapChange( change );
    }

    customAnalyze( meta, rootNode );

    return rootNode;

  }

  protected void processUsedFields( Set<StepField> usedFields ) {
    for ( StepField usedField : usedFields ) {
      IMetaverseNode usedNode = getInputs().findNode( usedField );
      if ( usedNode != null ) {
        getMetaverseBuilder().addLink( rootNode, DictionaryConst.LINK_USES, usedNode );
      }
    }
  }

  protected abstract Set<StepField> getUsedFields( T meta );

  protected abstract void customAnalyze( T meta, IMetaverseNode rootNode ) throws MetaverseAnalyzerException;

  @Override
  public void postAnalyze( T meta ) throws MetaverseAnalyzerException {
    // no-op, can be overridden in the child class
  }

  /**
   * Get all of the changes that need to be made to the metaverse. These are all of the "field---derives--->field"
   * changes
   *
   * @return
   */
  protected Set<ComponentDerivationRecord> getChanges() {
    Set<ComponentDerivationRecord> changes = new HashSet<>();
    try {
      Set<ComponentDerivationRecord> changeRecords = getChangeRecords( baseStepMeta );
      if ( CollectionUtils.isNotEmpty( changeRecords ) ) {
        changes.addAll( changeRecords );
      }
    } catch ( MetaverseAnalyzerException e ) {
      LOGGER.warn( "Error getting change records", e );
    }

    Set<ComponentDerivationRecord> passthroughChanges = getPassthroughChanges();
    if ( CollectionUtils.isNotEmpty( passthroughChanges ) ) {
      changes.addAll( passthroughChanges );
    }
    return changes;
  }

  /**
   * Get ComponentDerivationRecords for each of the fields considered to be a passthrough
   *
   * @return
   */
  protected Set<ComponentDerivationRecord> getPassthroughChanges() {
    Set<ComponentDerivationRecord> passthroughs = new HashSet<>();
    if ( getInputs() != null ) {
      Set<StepField> incomingFieldNames = getInputs().getFieldNames();
      for ( StepField incomingFieldName : incomingFieldNames ) {
        if ( isPassthrough( incomingFieldName ) ) {
          ComponentDerivationRecord change =
            new ComponentDerivationRecord( incomingFieldName.getFieldName(), incomingFieldName.getFieldName() );
          change.setOriginalEntityStepName( incomingFieldName.getStepName() );
          passthroughs.add( change );
        }
      }
    }
    return passthroughs;
  }

  /**
   * Determines if a field is considered a passthrough field or not. If the field name in question exists in the output
   * (exact match), then it is considered a passthrough.
   *
   * @param originalFieldName
   * @return
   */
  protected boolean isPassthrough( StepField originalFieldName ) {
    if ( getOutputs() != null ) {
      Set<StepField> fieldNames = getOutputs().getFieldNames();
      for ( StepField fieldName : fieldNames ) {
        if ( fieldName.getFieldName().equals( originalFieldName.getFieldName() ) ) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Add the required "derives" links to the metaverse for a ComponentDerivationRecord
   *
   * @param change
   */
  protected void mapChange( ComponentDerivationRecord change ) {
    if ( change != null ) {
      List<IMetaverseNode> inputNodes = new ArrayList<>();
      List<IMetaverseNode> outputNodes = new ArrayList<>();

      if ( StringUtils.isNotEmpty( change.getOriginalEntityStepName() ) ) {
        final IMetaverseNode inputNode = getInputs().findNode( change.getOriginalField() );
        if ( inputNode != null ) {
          inputNodes.add( inputNode );
        }
      } else {
        inputNodes.addAll( getInputs().findNodes( change.getOriginalEntityName() ) );
      }

      if ( StringUtils.isNotEmpty( change.getChangedEntityStepName() ) ) {
        final IMetaverseNode outputNode = getOutputs().findNode( change.getChangedField() );
        if ( outputNode != null ) {
          outputNodes.add( outputNode );
        }
      } else {
        outputNodes.addAll( getOutputs().findNodes( change.getChangedEntityName() ) );
      }

      if ( CollectionUtils.isEmpty( inputNodes ) ) {
        // see if it's one of the output nodes
        inputNodes = getOutputs().findNodes( change.getOriginalEntityName() );

        // if we still don't have it, we need a transient node created
        if ( CollectionUtils.isEmpty( inputNodes ) ) {
          // create a transient node for it
          ValueMetaInterface tmp = new ValueMeta( change.getOriginalEntityName() );
          IMetaverseNode fieldNode =
            createOutputFieldNode( getDescriptor().getContext(), tmp, null, getTransientNodeType() );
          // Add link to show that this step created this as a transient field
          getMetaverseBuilder().addLink( rootNode, DictionaryConst.LINK_TRANSIENT, fieldNode );
          getMetaverseBuilder().addLink( rootNode, DictionaryConst.LINK_USES, fieldNode );
          inputNodes.add( fieldNode );
        }
      }

      if ( CollectionUtils.isEmpty( outputNodes ) ) {
        // create a transient node for it
        ValueMetaInterface tmp = new ValueMeta( change.getChangedEntityName() );
        IMetaverseNode fieldNode =
          createOutputFieldNode( getDescriptor().getContext(), tmp, null, getTransientNodeType() );
        // Add link to show that this step created this as a transient field
        getMetaverseBuilder().addLink( rootNode, DictionaryConst.LINK_TRANSIENT, fieldNode );
        getMetaverseBuilder().addLink( rootNode, DictionaryConst.LINK_USES, fieldNode );
        outputNodes.add( fieldNode );
      }

      // no input step was defined, link all field name matches together, regardless of origin step
      for ( IMetaverseNode inputNode : inputNodes ) {
        for ( IMetaverseNode outputNode : outputNodes ) {
          if ( change.getOperations().size() > 0 ) {
            outputNode.setProperty( DictionaryConst.PROPERTY_OPERATIONS, change.toString() );
          }
          linkChangeNodes( inputNode, outputNode );
        }
      }

    }
  }

  protected void linkChangeNodes( IMetaverseNode inputNode, IMetaverseNode outputNode ) {
    getMetaverseBuilder().addLink( inputNode, getInputToOutputLinkLabel(), outputNode );
  }

  /**
   * Add new nodes to the metaverse for each of the fields that are output from this step. The fields are uniquely
   * identified based on the step that created the node and the intended target step.
   *
   * @param meta
   * @return
   */
  protected StepNodes processOutputs( T meta ) {
    StepNodes outputs = new StepNodes();

    Map<String, RowMetaInterface> outputRowMetaInterfaces = getOutputRowMetaInterfaces( meta );
    if ( MapUtils.isNotEmpty( outputRowMetaInterfaces ) ) {
      for ( Map.Entry<String, RowMetaInterface> entry : outputRowMetaInterfaces.entrySet() ) {
        String nextStepName = entry.getKey();
        RowMetaInterface outputFields = entry.getValue();
        if ( outputFields != null ) {
          for ( ValueMetaInterface valueMetaInterface : outputFields.getValueMetaList() ) {

            IMetaverseNode fieldNode =
              createOutputFieldNode( getDescriptor().getContext(), valueMetaInterface, nextStepName,
                getOutputNodeType() );
            // Add link to show that this step created the field
            getMetaverseBuilder().addLink( rootNode, DictionaryConst.LINK_OUTPUTS, fieldNode );
            outputs.addNode( nextStepName, valueMetaInterface.getName(), fieldNode );
          }
        } else {
          LOGGER.warn( "No output fields found for step " + getStepName() );
        }
      }
    }
    return outputs;
  }

  protected IMetaverseNode createInputFieldNode( IAnalysisContext context, ValueMetaInterface fieldMeta,
                                                 String previousStepName, String nodeType ) {
    IComponentDescriptor prevFieldDescriptor = getPrevFieldDescriptor( previousStepName, fieldMeta.getName() );
    return createFieldNode( prevFieldDescriptor, fieldMeta, getStepName(), false );
  }

  protected IMetaverseNode createOutputFieldNode( IAnalysisContext context, ValueMetaInterface fieldMeta,
                                                  String targetStepName, String nodeType ) {
    IComponentDescriptor fieldDescriptor =
      new MetaverseComponentDescriptor( fieldMeta.getName(), nodeType, rootNode, context );
    return createFieldNode( fieldDescriptor, fieldMeta, targetStepName, true );
  }

  @Override
  protected IMetaverseNode createNodeFromDescriptor( IComponentDescriptor descriptor ) {
    return super.createNodeFromDescriptor( descriptor );
  }

  protected IMetaverseNode createFieldNode( IComponentDescriptor fieldDescriptor, ValueMetaInterface fieldMeta,
                                            String targetStepName, boolean addTheNode ) {
    return createFieldNode( fieldDescriptor, fieldMeta.getTypeDesc(), targetStepName, addTheNode );
  }

  protected IMetaverseNode createFieldNode( IComponentDescriptor fieldDescriptor, String kettleType,
                                            String targetStepName, boolean addTheNode ) {

    IMetaverseNode newFieldNode = createNodeFromDescriptor( fieldDescriptor );
    newFieldNode.setProperty( DictionaryConst.PROPERTY_KETTLE_TYPE, kettleType );

    // don't add it to the graph if it is a transient node
    if ( targetStepName != null ) {
      newFieldNode.setProperty( DictionaryConst.PROPERTY_TARGET_STEP, targetStepName );
      newFieldNode.setLogicalIdGenerator( DictionaryConst.LOGICAL_ID_GENERATOR_TARGET_AWARE );
      if ( addTheNode ) {
        getMetaverseBuilder().addNode( newFieldNode );
      }
    }

    return newFieldNode;
  }

  /**
   * Add links to nodes in the metaverse for each of the fields that are input into this step. The fields are uniquely
   * identified based on the step that created the node and the intended target step.
   *
   * @param meta
   * @return
   */
  protected StepNodes processInputs( T meta ) {
    StepNodes inputs = new StepNodes();

    // get all input steps
    Map<String, RowMetaInterface> inputRowMetaInterfaces = getInputRowMetaInterfaces( meta );

    if ( MapUtils.isNotEmpty( inputRowMetaInterfaces ) ) {
      for ( Map.Entry<String, RowMetaInterface> entry : inputRowMetaInterfaces.entrySet() ) {
        String prevStepName = entry.getKey();
        RowMetaInterface inputFields = entry.getValue();
        if ( inputFields != null ) {
          String[] stepInputFieldNames = inputFields.getFieldNames();
          try {
            if ( !ExternalResourceStepAnalyzer.RESOURCE.equals( prevStepName ) ) {
              final RowMetaInterface stepInputFields = parentTransMeta.getPrevStepFields(
                parentStepMeta, prevStepName, null );
              if ( stepInputFields != null ) {
                stepInputFieldNames = stepInputFields.getFieldNames();
              }
            }
          } catch ( final KettleStepException e ) {
            // no-op
          }
          for ( ValueMetaInterface valueMetaInterface : inputFields.getValueMetaList() ) {
            boolean addLink = Arrays.asList( stepInputFieldNames ).contains( valueMetaInterface.getName() );
            IMetaverseNode prevFieldNode =
              createInputFieldNode( getDescriptor().getContext(), valueMetaInterface, prevStepName,
                getInputNodeType() );
            if ( addLink ) {
              getMetaverseBuilder().addLink( prevFieldNode, DictionaryConst.LINK_INPUTS, rootNode );
              inputs.addNode( prevStepName, valueMetaInterface.getName(), prevFieldNode );
            }
          }
        } else {
          LOGGER.warn( "No input fields found for step " + getStepName() );
        }
      }
    }

    return inputs;
  }

  /**
   * Create a new IComponentDescriptor for a field input into this step
   *
   * @param prevStepName
   * @param fieldName
   * @return
   */
  protected IComponentDescriptor getPrevFieldDescriptor( String prevStepName, String fieldName ) {
    IComponentDescriptor prevFieldDescriptor = null;
    if ( StringUtils.isNotEmpty( prevStepName ) ) {
      Object nsObj = rootNode.getProperty( DictionaryConst.PROPERTY_NAMESPACE );
      INamespace ns = new Namespace( nsObj != null ? nsObj.toString() : null );
      IMetaverseNode tmpOriginNode =
        getMetaverseObjectFactory().createNodeObject( ns, prevStepName, DictionaryConst.NODE_TYPE_TRANS_STEP );

      INamespace stepFieldNamespace = new Namespace( tmpOriginNode.getLogicalId() );

      prevFieldDescriptor =
        new MetaverseComponentDescriptor( fieldName, getInputNodeType(), stepFieldNamespace, getDescriptor()
          .getContext() );

    }
    return prevFieldDescriptor;
  }

  /**
   * Returns a {@link Set} of step names that contain the given {@code fieldName}.
   *
   * @param meta      a concrete instance of {@link BaseStepMeta}
   * @param fieldName the field name used to find matching steps
   * @return a {@link Set} of step names that contain the given {@code fieldName}.
   */
  public Set<String> getInputStepNames( final T meta, final String fieldName ) {
    // get all input steps
    final Map<String, RowMetaInterface> inputRowMetaInterfaces = getInputRowMetaInterfaces( meta );
    final Set<String> prevStepNames = new HashSet<>();
    if ( MapUtils.isNotEmpty( inputRowMetaInterfaces ) ) {
      for ( Map.Entry<String, RowMetaInterface> entry : inputRowMetaInterfaces.entrySet() ) {
        final String prevStepName = entry.getKey();
        final RowMetaInterface inputFields = entry.getValue();
        if ( inputFields != null ) {
          for ( ValueMetaInterface valueMetaInterface : inputFields.getValueMetaList() ) {
            if ( valueMetaInterface.getName().equalsIgnoreCase( fieldName ) ) {
              prevStepNames.add( prevStepName );
            }
          }
        }
      }
    }
    return prevStepNames;
  }

  public String getStepName() {
    return parentStepMeta.getName();
  }

  public StepNodes getInputs() {
    return inputs;
  }

  public StepNodes getOutputs() {
    return outputs;
  }

  public void setDescriptor( IComponentDescriptor descriptor ) {
    this.descriptor = descriptor;
  }

  public IComponentDescriptor getDescriptor() {
    return descriptor;
  }

  public Set<StepField> createStepFields( String fieldName, StepNodes stepNodes ) {
    Set<StepField> fields = new HashSet<>();
    for ( String stepName : stepNodes.getStepNames() ) {
      fields.add( new StepField( stepName, fieldName ) );
    }
    return fields;
  }

  protected String getInputToOutputLinkLabel() {
    return DictionaryConst.LINK_DERIVES;
  }

  protected String getInputNodeType() {
    return DictionaryConst.NODE_TYPE_TRANS_FIELD;
  }

  protected String getOutputNodeType() {
    return DictionaryConst.NODE_TYPE_TRANS_FIELD;
  }

  protected String getTransientNodeType() {
    return DictionaryConst.NODE_TYPE_TRANS_FIELD;
  }


  protected Map<String, RowMetaInterface> getOutputRowMetaInterfaces( T meta ) {
    return getOutputRowMetaInterfaces( parentTransMeta, parentStepMeta, meta, true );
  }

  protected Map<String, RowMetaInterface> getOutputRowMetaInterfaces(
    final TransMeta transMeta, final StepMeta stepMeta, final BaseStepMeta meta, boolean validateState ) {
    String[] nextStepNames = transMeta.getNextStepNames( stepMeta );
    Map<String, RowMetaInterface> outputRows = new HashMap<>();
    RowMetaInterface outputFields = validateState ? getOutputFields( (T) meta ) : getOutputFields( transMeta,
      stepMeta );

    if ( outputFields != null && ArrayUtils.isEmpty( nextStepNames ) ) {
      nextStepNames = new String[] { NONE };
    }
    for ( String stepName : nextStepNames ) {
      outputRows.put( stepName, outputFields );
    }
    return outputRows;
  }

  protected Map<String, RowMetaInterface> getInputRowMetaInterfaces( T meta ) {
    Map<String, RowMetaInterface> inputFields = getInputFields( meta );
    return inputFields;
  }

  protected void setMetaverseObjectFactory( IMetaverseObjectFactory factory ) {
    metaverseObjectFactory = factory;
  }

  public void validateState( IComponentDescriptor descriptor, T object ) throws MetaverseAnalyzerException {
    baseStepMeta = object;
    if ( baseStepMeta == null ) {
      throw new MetaverseAnalyzerException( Messages.getString( "ERROR.StepMetaInterface.IsNull" ) );
    }

    parentStepMeta = baseStepMeta.getParentStepMeta();
    if ( parentStepMeta == null ) {
      throw new MetaverseAnalyzerException( Messages.getString( "ERROR.StepMeta.IsNull" ) );
    }

    parentTransMeta = parentStepMeta.getParentTransMeta();

    if ( parentTransMeta == null ) {
      throw new MetaverseAnalyzerException( Messages.getString( "ERROR.ParentTransMeta.IsNull" ) );
    }

    if ( metaverseBuilder == null ) {
      throw new MetaverseAnalyzerException( Messages.getString( "ERROR.MetaverseBuilder.IsNull" ) );
    }

    if ( metaverseObjectFactory == null ) {
      throw new MetaverseAnalyzerException( Messages.getString( "ERROR.MetaverseObjectFactory.IsNull" ) );
    }
  }

  public IConnectionAnalyzer getConnectionAnalyzer() {
    return connectionAnalyzer;
  }

  public void setConnectionAnalyzer( IConnectionAnalyzer connectionAnalyzer ) {
    this.connectionAnalyzer = connectionAnalyzer;
  }

  public Map<String, RowMetaInterface> getInputFields( final TransMeta parentTransMeta,
                                                       final StepMeta parentStepMeta ) {
    Map<String, RowMetaInterface> rowMeta = null;
    if ( parentTransMeta != null ) {
      try {
        rowMeta = new HashMap();
        ProgressNullMonitorListener progressMonitor = new ProgressNullMonitorListener();
        prevStepNames = parentTransMeta.getPrevStepNames( parentStepMeta );
        RowMetaInterface rmi = parentTransMeta.getPrevStepFields( parentStepMeta, progressMonitor );
        progressMonitor.done();
        if ( !ArrayUtils.isEmpty( prevStepNames ) ) {
          populateInputFieldsRowMeta( rowMeta, rmi );
        }
      } catch ( KettleStepException e ) {
        rowMeta = null;
      }
    }
    return rowMeta;
  }

  @Override
  public Map<String, RowMetaInterface> getInputFields( T meta ) {
    try {
      validateState( null, meta );
    } catch ( MetaverseAnalyzerException e ) {
      // ignore
    }
    return getInputFields( parentTransMeta, parentStepMeta );
  }

  /**
   * Populates the {@code rowMeta} with data from all input steps, can be overridden to do otherwise.
   */
  protected void populateInputFieldsRowMeta( final Map<String, RowMetaInterface> rowMeta, final RowMetaInterface rmi ) {
    for ( final String previousStepName : prevStepNames ) {
      rowMeta.put( previousStepName, rmi );
    }
  }

  @Override
  public RowMetaInterface getOutputFields( T meta ) {
    RowMetaInterface rmi = null;
    try {
      validateState( null, meta );
    } catch ( MetaverseAnalyzerException e ) {
      // eat it
    }
    return getOutputFields( parentTransMeta, parentStepMeta );
  }

  protected RowMetaInterface getOutputFields(
    final TransMeta transMeta, final StepMeta stepMeta ) {
    RowMetaInterface rmi = null;
    if ( transMeta != null ) {
      try {
        ProgressNullMonitorListener progressMonitor = new ProgressNullMonitorListener();
        rmi = transMeta.getStepFields( stepMeta, progressMonitor );
        progressMonitor.done();
      } catch ( KettleStepException e ) {
        rmi = null;
      }
    }
    return rmi;
  }

  @Override
  public Set<IFieldMapping> getFieldMappings( T meta ) throws MetaverseAnalyzerException {
    return null;
  }

  @Override
  public Set<ComponentDerivationRecord> getChangeRecords( T meta ) throws MetaverseAnalyzerException {
    return null;
  }

  /**
   * Loads the in/out fields for this step into member variables for use by the analyzer
   */
  public void loadInputAndOutputStreamFields( T meta ) {
    prevFields = getInputFields( meta );
    stepFields = getOutputFields( meta );
  }

  protected IComponentDescriptor getStepFieldOriginDescriptor( IComponentDescriptor descriptor, String fieldName )
    throws MetaverseAnalyzerException {

    if ( descriptor == null || stepFields == null ) {
      return null;
    }
    ValueMetaInterface vmi = stepFields.searchValueMeta( fieldName );
    String origin = ( vmi == null ) ? fieldName : vmi.getOrigin();

    // if we can't determine the origin, throw an exception
    if ( origin == null && !ArrayUtils.isEmpty( prevStepNames ) ) {
      throw new MetaverseAnalyzerException( Messages.getString( "ERROR.NoOriginForField", fieldName ) );
    }

    IMetaverseNode tmpOriginNode =
      metaverseObjectFactory.createNodeObject( UUID.randomUUID().toString(), origin,
        DictionaryConst.NODE_TYPE_TRANS_STEP );
    tmpOriginNode.setProperty( DictionaryConst.PROPERTY_NAMESPACE, rootNode
      .getProperty( DictionaryConst.PROPERTY_NAMESPACE ) );
    INamespace stepFieldNamespace = new Namespace( tmpOriginNode.getLogicalId() );

    MetaverseComponentDescriptor d =
      new MetaverseComponentDescriptor( fieldName, DictionaryConst.NODE_TYPE_TRANS_FIELD, tmpOriginNode, descriptor
        .getContext() );
    return d;
  }

  @Override
  public final IClonableStepAnalyzer cloneAnalyzer() {
    final IClonableStepAnalyzer newInstance = newInstance();
    copyState( newInstance );
    return newInstance;
  }

  /**
   * Returns this {@link IClonableStepAnalyzer} by default and should be overridden by concrete implementations to
   * create a new instance.
   *
   * @return this {@link IClonableStepAnalyzer} by default and should be overridden by concrete implementations to
   * create a new instance.
   */
  protected IClonableStepAnalyzer newInstance() {
    return this;
  }

  /**
   * Copies the any relevant properties from this {@link IClonableStepAnalyzer} to the {@code newAnalyzer}
   *
   * @param newAnalyzer the {@link IClonableStepAnalyzer} into which the properties from this {@link
   *                    IClonableStepAnalyzer} are being copied.
   * @return true if the properties were copied, false otherwise
   */
  protected boolean copyState( final IClonableStepAnalyzer newAnalyzer ) {
    if ( newAnalyzer instanceof StepAnalyzer ) {
      ( (StepAnalyzer) newAnalyzer ).setConnectionAnalyzer( getConnectionAnalyzer() );
      return true;
    }
    return false;
  }

  /**
   * Returns a {@link IMetaverseNode} from the map (if present) or created a new one.
   *
   * @param name        the node name
   * @param type        the node type (Transformation Step, Transformaton Stream Field, Database column etc...)
   * @param namespaceId the node namespace id
   * @param nodeKey     the lookup key for the map
   * @param nodeMap     a {@link Map} of nodes for lookup
   * @return a {@link IMetaverseNode} from the map (if present) or created a new one.
   */
  protected IMetaverseNode getNode( final String name, final String type, final String namespaceId,
                                    final String nodeKey, final Map<String, IMetaverseNode> nodeMap ) {
    return getNode( name, type, new Namespace( namespaceId ), nodeKey, nodeMap );
  }

  /**
   * Returns a {@link IMetaverseNode} from the map (if present) or created a new one.
   *
   * @param name      the node name
   * @param type      the node type (Transformation Step, Transformaton Stream Field, Database column etc...)
   * @param namespace the node'as {@link INamespace}
   * @param nodeKey   the lookup key for the map
   * @param nodeMap   a {@link Map} of nodes for lookup
   * @return a {@link IMetaverseNode} from the map (if present) or created a new one.
   */
  public IMetaverseNode getNode( final String name, final String type, final INamespace namespace,
                                 final String nodeKey, final Map<String, IMetaverseNode> nodeMap ) {
    IMetaverseNode node = nodeMap == null ? null : nodeMap.get( nodeKey );
    if ( node == null ) {
      node = createNode( name, type, namespace );
      if ( nodeMap != null ) {
        nodeMap.put( nodeKey, node );
      }
    }
    return node;
  }

  /**
   * Created a new instance of {@link IMetaverseNode}.
   *
   * @param name      the node name
   * @param type      the node type (Transformation Step, Transformaton Stream Field, Database column etc...)
   * @param namespace the node'as {@link INamespace}
   * @return a new instance of {@link IMetaverseNode}
   */
  protected IMetaverseNode createNode( final String name, final String type, final INamespace namespace ) {
    final IComponentDescriptor descriptor = new MetaverseComponentDescriptor( name, type, namespace );
    final IMetaverseNode node = createNodeFromDescriptor( descriptor );
    node.setProperty( DictionaryConst.NODE_VIRTUAL, false );
    return node;
  }

  @Override
  public void setDocumentAnalyzer( final IClonableDocumentAnalyzer documentAnalyzer ) {
    this.documentAnalyzer = documentAnalyzer;
  }

  @Override
  public IClonableDocumentAnalyzer getDocumentAnalyzer() {
    return this.documentAnalyzer;
  }

  @Override
  public void setDocumentDescriptor( final IComponentDescriptor documentDescriptor ) {
    this.documentDescriptor = documentDescriptor;
  }

  @Override
  public IComponentDescriptor getDocumentDescriptor() {
    return this.documentDescriptor;
  }

  @Override
  public void setDocumentPath( final String documentPath ) {
    this.documentPath = documentPath;
  }

  /**
   * Finds {@link Vertex}es within the {@link com.tinkerpop.blueprints.Graph} associated with this analyzer's builder
   * with matching properties.
   *
   * @param properties a {@link Map} of lookup properties
   * @return a @{link List} of {@link Vertex} objects containing the requested properties
   */
  protected List<Vertex> findVertices( final Map<String, String> properties ) {
    return findVertices( getMetaverseBuilder().getGraph().getVertices().iterator(), properties );
  }

  /**
   * Finds {@link Vertex}es from within the provided {@code vertices} {@link List} with matching properties.
   *
   * @param properties a {@link Map} of lookup properties
   * @return a @{link List} of {@link Vertex} objects containing the requested properties
   */
  protected List<Vertex> findVertices( final Iterator<Vertex> vertices, final Map<String, String> properties ) {
    final List<Vertex> matchingNodes = new ArrayList();

    outer:
    while ( vertices.hasNext() ) {
      final Vertex vertex = vertices.next();

      if ( properties != null ) {
        final Iterator<Map.Entry<String, String>> propsIter = properties.entrySet().iterator();
        while ( propsIter.hasNext() ) {
          final Map.Entry<String, String> property = propsIter.next();
          final String propName = property.getKey();
          final String propValue = property.getValue();
          if ( vertex.getProperty( propName ) == null || !vertex.getProperty( propName ).equals( propValue ) ) {
            continue outer;
          }
        }
      }
      // all properties should match
      matchingNodes.add( vertex );
    }
    return matchingNodes;
  }

  /**
   * Finds a {@link Vertex} representing a step with the given name, within the given {@link TransMeta}.
   *
   * @param transMeta a {@link TransMeta} containing steps
   * @param stepName  the step name being looked up
   * @return the first {@link Vertex} representing a step with the given name, with the given {@link TransMeta}
   */
  protected Vertex findStepVertex( final TransMeta transMeta, final String stepName ) {
    final Map<String, String> propsLookupMap = new HashMap();
    propsLookupMap.put( DictionaryConst.PROPERTY_NAME, stepName );
    return findStepVertex( transMeta, propsLookupMap );
  }

  /**
   * Finds a {@link Vertex} representing a step with the given properties, within the given {@link TransMeta}.
   *
   * @param transMeta  a {@link TransMeta} containing steps
   * @param properties a {@link Map} of lookup properties
   * @return the first {@link Vertex} representing a step with the given name, with the given properties
   */
  protected Vertex findStepVertex( final TransMeta transMeta, final Map<String, String> properties ) {
    final List<Vertex> matchingVertices = findStepVertices( transMeta, properties );
    if ( matchingVertices.size() > 0 ) {
      if ( matchingVertices.size() > 1 ) {
        LOGGER.warn( Messages.getString( "WARN.MultipleMatchingStepVerticesFound", transMeta.getName(),
          Joiner.on( ", " ).withKeyValueSeparator( ": " ).join( properties == null ? new HashMap() : properties ) ) );
      }
      return matchingVertices.get( 0 );
    }
    return null;
  }

  /**
   * Finds {@link Vertex}es representing steps with ths given properties, within the given {@link TransMeta}.
   *
   * @param transMeta  a {@link TransMeta} containing steps
   * @param properties a {@link Map} of lookup properties
   * @return a @{link List} of step {@link Vertex} objects containing the requested properties
   */
  protected List<Vertex> findStepVertices( final TransMeta transMeta, final Map<String, String> properties ) {

    final List<Vertex> matchingNodes = new ArrayList();
    final Map<String, String> propsLookupMap = properties == null ? new HashMap() : new HashMap( properties );
    propsLookupMap.put( DictionaryConst.PROPERTY_TYPE, DictionaryConst.NODE_TYPE_TRANS_STEP );
    final List<Vertex> potentialMatches = findVertices( propsLookupMap );
    final String transPath = KettleAnalyzerUtil.normalizeFilePathSafely( transMeta.getBowl(), transMeta.getFilename() );
    // inspect input "contains" links for each vertex, when a "containing" transformation with a matching path is
    // found, we have the  vertex we need
    for ( final Vertex potentialMatch : potentialMatches ) {
      final Iterator<Vertex> containingVertices = potentialMatch.getVertices( Direction.IN,
        DictionaryConst.LINK_CONTAINS ).iterator();
      while ( containingVertices.hasNext() ) {
        final Vertex containingVertex = containingVertices.next();
        final String containingVertexPath = KettleAnalyzerUtil.normalizeFilePathSafely(
          transMeta.getBowl(), containingVertex.getProperty( DictionaryConst.PROPERTY_PATH ) );
        if ( transPath.equalsIgnoreCase( containingVertexPath ) ) {
          matchingNodes.add( potentialMatch );
        }
      }
    }
    return matchingNodes;
  }

  /**
   * Finds a {@link Vertex} representing a step with the given fieldName, within a step with the given stepName, within
   * the given {@link TransMeta}.
   *
   * @param transMeta a {@link TransMeta} containing steps
   * @param stepName  the containing step name
   * @param fieldName the field name being looked up
   * @return the first {@link Vertex} representing a step with the given fieldName, within a step with the given
   * stepName, within the given {@link TransMeta}
   */
  protected Vertex findFieldVertex( final TransMeta transMeta, final String stepName,
                                    final String fieldName ) {

    final Map<String, String> propsLookupMap = new HashMap();
    propsLookupMap.put( DictionaryConst.PROPERTY_NAME, fieldName );
    return findFieldVertex( transMeta, stepName, propsLookupMap );
  }

  /**
   * Finds a {@link Vertex} representing a field with the given step name and properties, within the given {@link
   * TransMeta}.
   *
   * @param transMeta  a {@link TransMeta} containing steps
   * @param stepName   containing step name
   * @param properties a {@link Map} of lookup properties
   * @return the first {@link Vertex} representing a field with the given step namd and properties, within the given
   * {@link TransMeta}.
   */
  protected Vertex findFieldVertex( final TransMeta transMeta, final String stepName,
                                    final Map<String, String> properties ) {
    final List<Vertex> matchingVertices = findFieldVertices( transMeta, stepName, properties );
    if ( matchingVertices.size() > 0 ) {
      if ( matchingVertices.size() > 1 ) {
        LOGGER.warn( Messages.getString( "WARN.MultipleMatchingFieldVerticesFound", transMeta.getName(),
          stepName, Joiner.on( ", " ).withKeyValueSeparator( ": " ).join( properties == null
            ? new HashMap() : properties ) ) );
      }
      return matchingVertices.get( 0 );
    }
    return null;
  }

  /**
   * Finds {@link Vertex}es representing fields with the given step name and properties, within the given {@link
   * TransMeta}.
   *
   * @param transMeta  a {@link TransMeta} containing steps
   * @param stepName   containing step name
   * @param properties a {@link Map} of lookup properties
   * @return a @{link List} of field {@link Vertex} objects with the given step name and containing the requested
   * properties
   */
  protected List<Vertex> findFieldVertices( final TransMeta transMeta, final String stepName,
                                            final Map<String, String> properties ) {

    final Vertex stepVertex = findStepVertex( transMeta, stepName );

    final Map<String, String> propsLookupMap = properties == null ? new HashMap() : new HashMap( properties );
    propsLookupMap.put( DictionaryConst.PROPERTY_TYPE, DictionaryConst.NODE_TYPE_TRANS_FIELD );
    return findVertices( stepVertex.getVertices( Direction.OUT, DictionaryConst.LINK_OUTPUTS ).iterator(),
      propsLookupMap );
  }

  /**
   * Returns the {@link Vertex} with the matching id.
   *
   * @param id the id being looked up
   * @return the {@link Vertex} with the matching id or null
   */
  protected Vertex findVertexById( final String id ) {

    final Iterator<Vertex> allVertices = getMetaverseBuilder().getGraph().getVertices().iterator();
    while ( allVertices.hasNext() ) {
      final Vertex vertex = allVertices.next();
      if ( vertex.getId().equals( id ) ) {
        return vertex;
      }
    }
    return null;
  }

  /**
   * Finds all fields {@link Vertex}es for the given step, within the given {@link TransMeta}.
   *
   * @param transMeta a {@link TransMeta} containing steps
   * @param stepName  containing step name
   * @return a @{link List} of field {@link Vertex} objects with the given step name
   */
  protected List<Vertex> findFieldVertices( final TransMeta transMeta, final String stepName ) {
    return findFieldVertices( transMeta, stepName, null );
  }

  /**
   * Sets the {@link Vertex} property safely, with all the proper null checks.
   *
   * @param vertex        the {@link Vertex} whose property is being set
   * @param propertyName  the property name
   * @param propertyValue the property value
   * @return true if the property was set succesfully, false otherwise
   */
  protected boolean setPropertySafely( final Vertex vertex, final String propertyName, final String propertyValue ) {
    if ( vertex == null || propertyName == null || propertyValue == null ) {
      return false;
    }
    vertex.setProperty( propertyName, propertyValue );
    return true;
  }
}
