/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright 2008 Sun Microsystems, Inc.  All rights reserved.
 * Use is subject to license terms.
 */

package org.opensolaris.opengrok.management;

import java.io.IOException;
import org.opensolaris.opengrok.configuration.Configuration;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;

/**
 *
 * @author Jan Berg
 */
public class JMXConfiguration implements JMXConfigurationMBean {

    public String getConfiguration() {
        return RuntimeEnvironment.getInstance().getConfiguration().getXMLRepresentationAsString();
    }

    public void setConfiguration(String config) throws IOException {
        Configuration configuration = Configuration.makeXMLStringAsConfiguration(config);
        RuntimeEnvironment.getInstance().setConfiguration(configuration);
    }
}