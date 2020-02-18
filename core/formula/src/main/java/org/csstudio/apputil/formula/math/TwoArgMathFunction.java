/*******************************************************************************
 * Copyright (c) 2019-2020 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.csstudio.apputil.formula.math;

import java.util.List;

import org.csstudio.apputil.formula.spi.FormulaFunction;
import org.epics.vtype.Alarm;
import org.epics.vtype.Display;
import org.epics.vtype.Time;
import org.epics.vtype.VDouble;
import org.epics.vtype.VType;
import org.phoebus.core.vtypes.VTypeHelper;

/** Helper for SPI-provided `double function(double, double)`
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
class TwoArgMathFunction implements FormulaFunction
{
    @FunctionalInterface
    public interface TwoArgFunction
    {
        double calc(double a, double b);
    }

    private final String name;
    private final String description;
    private final TwoArgFunction function;

    protected TwoArgMathFunction(final String name, final String description, final TwoArgFunction function)
    {
        this.name = name;
        this.description = description;
        this.function = function;
    }

    @Override
    public String getCategory() {
        return "math";
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public String getDescription()
    {
        return description;
    }

    @Override
    public List<String> getArguments()
    {
        return List.of("x", "y");
    }

    @Override
    public VType compute(final VType... args) throws Exception
    {
        final double a = VTypeHelper.getDouble(args[0]);
        final double b = VTypeHelper.getDouble(args[1]);
        final double value = function.calc(a, b);
        return VDouble.of(value, Alarm.none(), Time.now(), Display.none());
    }
}
