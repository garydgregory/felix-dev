/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.felix.utils.properties;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * Unit tests on <code>TypedProperties</code>.
 * </p>
 *
 * @author gnodet
 */
public class TypedPropertiesTest extends TestCase {

    private final static String LINE_SEPARATOR = System.getProperty("line.separator");
    private final static String TEST_PROPERTIES_FILE = "test.properties";
    private final static String TEST_TYPED_PROPERTIES_FILE = "typed.properties";

    public void testConfigInterpolation() throws IOException
    {
        TypedProperties properties = new TypedProperties();
        properties.load(this.getClass().getClassLoader().getResourceAsStream(TEST_TYPED_PROPERTIES_FILE));

        assertEquals(8101, properties.get("port"));
        assertEquals("127.0.0.1:8101", properties.get("url"));
    }

    public void testSetProp() throws IOException
    {
        TypedProperties properties = new TypedProperties();
        properties.load(this.getClass().getClassLoader().getResourceAsStream(TEST_TYPED_PROPERTIES_FILE));

        properties.put("port", 8101);
        properties.put("port2", 8103);
        properties.put("url", "127.0.0.1:8101");

        properties.save(System.out);
    }

    public void testLoadNonTypedProps() throws IOException
    {
        TypedProperties properties = new TypedProperties();
        properties.load(this.getClass().getClassLoader().getResourceAsStream(TEST_PROPERTIES_FILE));
        properties.put("port", 8101);

        StringWriter sw = new StringWriter();
        properties.save(sw);

        TypedProperties p2 = new TypedProperties();
        p2.load(new StringReader(sw.toString()));
        assertEquals(8101, p2.get("port"));
        assertEquals("test", p2.get("test"));
    }

    public void testLoadTypedProps2() throws IOException
    {
        TypedProperties properties = new TypedProperties();
        properties.load(this.getClass().getClassLoader().getResourceAsStream("typed2.properties"));
        assertEquals("wlp3s0", properties.get("networkInterface"));
    }

    public void testLoadTypedProps3() throws IOException
    {
        TypedProperties properties = new TypedProperties();
        properties.load(this.getClass().getClassLoader().getResourceAsStream("typed3.properties"));
        assertEquals(21, properties.get("bla"));
    }

    public void testWriteTypedPropsFloat() throws IOException
    {
        TypedProperties properties = new TypedProperties();
        properties.load(new StringReader("key = F\"1137191584\"" + LINE_SEPARATOR));
        assertEquals(400.333f, properties.get("key"));
    }

    public void testReadStringWithEqual() throws IOException
    {
        TypedProperties properties = new TypedProperties();
        properties.load(new StringReader("key = \"foo=bar\"" + LINE_SEPARATOR));
        assertEquals("foo=bar", properties.get("key"));
    }

    public void testWriteTypedPropsFloat2() throws IOException
    {
        TypedProperties properties = new TypedProperties();
        properties.put("key", 400.333f);
        StringWriter sw = new StringWriter();
        properties.save(sw);
        assertEquals("key = F\"400.333\"" + LINE_SEPARATOR, sw.toString());
        properties = new TypedProperties();
        properties.load(new StringReader(sw.toString()));
        assertEquals(400.333f, properties.get("key"));
    }

    public void testWriteTypedPropsIntegerList() throws IOException
    {
        List<Integer> list = new ArrayList<Integer>();
        list.add(1);
        list.add(2);
        list.add(3);
        TypedProperties properties = new TypedProperties();
        properties.put("key", list);
        StringWriter sw = new StringWriter();
        properties.save(sw);
        String str = "key = I( \\\r\n  \"1\", \\\r\n  \"2\", \\\r\n  \"3\", \\\r\n)" + LINE_SEPARATOR;
        assertEquals(str, sw.toString());
        properties = new TypedProperties();
        properties.load(new StringReader(sw.toString()));
        assertEquals(list, properties.get("key"));
    }

    public void testWriteTypedPropsFloatArray() throws IOException
    {
        Float[] array = new Float[] { 1.0f, 2.0f, 3.0f };
        TypedProperties properties = new TypedProperties();
        properties.put("key", array);
        StringWriter sw = new StringWriter();
        properties.save(sw);
        String str = "key = F[ \\\r\n  \"1.0\", \\\r\n  \"2.0\", \\\r\n  \"3.0\", \\\r\n  ]" + LINE_SEPARATOR;
        assertEquals(str, sw.toString());
        properties = new TypedProperties();
        properties.load(new StringReader(sw.toString()));
        assertTrue(Arrays.equals(array, (Object[]) properties.get("key")));
    }

    public void testSubstitution() throws IOException
    {
        String str = "port = 4141" + LINE_SEPARATOR +
                     "host = localhost" + LINE_SEPARATOR +
                     "url = https://${host}:${port}/service" + LINE_SEPARATOR;
        TypedProperties properties = new TypedProperties();
        properties.load(new StringReader(str));
        properties.put("url", "https://localhost:4141/service");
        StringWriter sw = new StringWriter();
        properties.save(sw);
        assertEquals(str, sw.toString());
    }

    public void testWriteTypedPropsStringWithSpaces() throws IOException
    {
        TypedProperties properties = new TypedProperties();
        properties.put("key", 3); // force the config to be typed
        properties.put("key", "s 1");
        StringWriter sw = new StringWriter();
        properties.save(sw);
        assertEquals("key = \"s 1\"" + LINE_SEPARATOR, sw.toString());
    }

}
