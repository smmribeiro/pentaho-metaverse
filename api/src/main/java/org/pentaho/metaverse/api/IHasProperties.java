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


package org.pentaho.metaverse.api;

import java.util.Map;
import java.util.Set;

/**
 * The IHasProperties interface offers common methods for getting and setting properties (key/value pairs)
 */
public interface IHasProperties {

  /**
   * Gets the property value for the specified key.
   *
   * @param key the lookup key
   * @return the value object for the property, or null if none is found
   */
  Object getProperty( String key );

  /**
   * Sets a value for the property with the given key.
   *
   * @param key   the property name for which to set the given value
   * @param value the value to assign to the property key
   */
  void setProperty( String key, Object value );

  /**
   * Removes and returns the value assigned to the property for the given key.
   *
   * @param key the key for which to remove the property's value
   * @return the value that was removed, or null if the key or value could not be found
   */
  Object removeProperty( String key );

  /**
   * Gets the set of keys (property names).
   *
   * @return a Set of property keys
   */
  Set<String> getPropertyKeys();

  /**
   * Returns the properties as a key/value Map.
   *
   * @return the property key/value assignments
   */
  Map<String, Object> getProperties();

  /**
   * Sets the given property keys to the given property values.
   *
   * @param properties
   */
  void setProperties( Map<String, Object> properties );

  /**
   * Removes the values assigned to the given property keys.
   *
   * @param keys
   */
  void removeProperties( Set<String> keys );

  /**
   * Removes all properties (key/value assignments).
   */
  void clearProperties();

  /**
   * Determine if a particular property is set on this node
   * @param key key to look up
   * @return true if it was found, false if not
   */
  boolean containsKey( String key );

  /**
   *
   * @return true if the node has had modifications to its properties, false if not
   */
  boolean isDirty();

  /**
   * Set the dirty state of the node
   * @param dirty true if it is dirty, false if it is not
   */
  void setDirty( boolean dirty );

}
