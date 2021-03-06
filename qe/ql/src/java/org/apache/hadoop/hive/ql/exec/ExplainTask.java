/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.Arrays;
import java.util.Comparator;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.plan.explain;
import org.apache.hadoop.hive.ql.plan.explainWork;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.util.StringUtils;

public class ExplainTask extends Task<explainWork> implements Serializable {
  private static final long serialVersionUID = 1L;

  public int execute() {

    try {
      OutputStream outS = work.getResFile().getFileSystem(conf)
          .create(work.getResFile());
      PrintStream out = new PrintStream(outS);

      outputAST(work.getAstStringTree(), out, 0);
      out.println();

      outputDependencies(out, work.getRootTasks(), 0);
      out.println();

      outputStagePlans(out, work.getRootTasks(), 0);
      out.close();

      return (0);
    } catch (Exception e) {
      console.printError("Failed with exception " + e.getMessage(), "\n"
          + StringUtils.stringifyException(e));
      if (SessionState.get() != null)
        SessionState.get().ssLog(
            "Failed with exception " + e.getMessage() + "\n"
                + StringUtils.stringifyException(e));
      return (1);
    }
  }

  private String indentString(int indent) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < indent; ++i) {
      sb.append(" ");
    }

    return sb.toString();
  }

  private void outputMap(Map<?, ?> mp, String header, PrintStream out,
      boolean extended, int indent) throws Exception {

    boolean first_el = true;
    for (Entry<?, ?> ent : mp.entrySet()) {
      if (first_el) {
        out.println(header);
      }
      first_el = false;

      out.print(indentString(indent));
      out.printf("%s ", ent.getKey().toString());
      if (isPrintable(ent.getValue())) {
        out.print(ent.getValue());
        out.println();
      } else if (ent.getValue() instanceof List) {
        out.print(ent.getValue().toString());
        out.println();
      } else if (ent.getValue() instanceof Serializable) {
        out.println();
        outputPlan((Serializable) ent.getValue(), out, extended, indent + 2);
      }
    }
  }

  private void outputList(List<?> l, String header, PrintStream out,
      boolean extended, int indent) throws Exception {

    boolean first_el = true;
    boolean nl = false;
    for (Object o : l) {
      if (first_el) {
        out.print(header);
      }

      if (isPrintable(o)) {
        if (!first_el) {
          out.print(", ");
        } else {
          out.print(" ");
        }

        out.print(o);
        nl = true;
      } else if (o instanceof Serializable) {
        if (first_el) {
          out.println();
        }
        outputPlan((Serializable) o, out, extended, indent + 2);
      }

      first_el = false;
    }

    if (nl) {
      out.println();
    }
  }

  private boolean isPrintable(Object val) {
    if (val == null)
      return false;
    if (val instanceof Boolean || val instanceof String
        || val instanceof Integer || val instanceof Byte
        || val instanceof Float || val instanceof Double) {
      return true;
    }

    if (val.getClass().isPrimitive()) {
      return true;
    }

    return false;
  }

  private void outputPlan(Serializable work, PrintStream out, boolean extended,
      int indent) throws Exception {
    Annotation note = work.getClass().getAnnotation(explain.class);

    if (note instanceof explain) {
      explain xpl_note = (explain) note;
      if (extended || xpl_note.normalExplain()) {
        out.print(indentString(indent));
        out.println(xpl_note.displayName());
      }
    }

    if (work instanceof Operator) {
      Operator<? extends Serializable> operator = (Operator<? extends Serializable>) work;

      if (operator.getConf() != null) {
        out.print(indentString(indent));
        out.print("Operator:");
        outputPlan(operator.getConf(), out, extended, indent);
      }
      if (operator.getChildOperators() != null) {
        for (Operator<? extends Serializable> op : operator.getChildOperators()) {
          outputPlan(op, out, extended, indent + 2);
        }
      }
      return;
    }

    Method[] methods = work.getClass().getMethods();
    Arrays.sort(methods, new MethodComparator());

    for (Method m : methods) {
      int prop_indents = indent + 2;
      note = m.getAnnotation(explain.class);

      if (note instanceof explain) {
        explain xpl_note = (explain) note;

        if (extended || xpl_note.normalExplain()) {

          Object val = m.invoke(work);

          if (val == null) {
            continue;
          }

          String header = null;
          if (!xpl_note.displayName().equals("")) {
            header = indentString(prop_indents) + xpl_note.displayName() + ":";
          } else {
            prop_indents = indent;
            header = indentString(prop_indents);
          }

          if (isPrintable(val)) {

            out.printf("%s ", header);
            out.println(val);
            continue;
          }
          try {
            Map<?, ?> mp = (Map<?, ?>) val;
            outputMap(mp, header, out, extended, prop_indents + 2);
            continue;
          } catch (ClassCastException ce) {
          }

          try {
            List<?> l = (List<?>) val;
            outputList(l, header, out, extended, prop_indents + 2);

            continue;
          } catch (ClassCastException ce) {
          }

          try {
            Serializable s = (Serializable) val;
            out.println(header);
            outputPlan(s, out, extended, prop_indents + 2);

            continue;
          } catch (ClassCastException ce) {
          }
        }
      }
    }
  }

  private void outputPlan(Task<? extends Serializable> task, PrintStream out,
      boolean extended, HashSet<Task<? extends Serializable>> displayedSet,
      int indent) throws Exception {

    if (displayedSet.contains(task)) {
      return;
    }
    displayedSet.add(task);

    out.print(indentString(indent));
    out.printf("Stage: %s\n", task.getId());
    outputPlan(task.getWork(), out, extended, indent + 2);
    out.println();

    if (task instanceof ConditionalTask
        && ((ConditionalTask) task).getListTasks() != null) {
      for (Task<? extends Serializable> con : ((ConditionalTask) task)
          .getListTasks()) {
        outputPlan(con, out, extended, displayedSet, indent);
      }
    }

    if (task.getChildTasks() != null) {
      for (Task<? extends Serializable> child : task.getChildTasks()) {
        outputPlan(child, out, extended, displayedSet, indent);
      }
    }
  }

  private Set<Task<? extends Serializable>> dependeciesTaskSet = new HashSet<Task<? extends Serializable>>();

  private void outputDependencies(Task<? extends Serializable> task,
      PrintStream out, int indent, boolean rootTskCandidate) throws Exception {

    if (dependeciesTaskSet.contains(task))
      return;
    dependeciesTaskSet.add(task);

    out.print(indentString(indent));
    out.println(task.getId());

    if ((task.getParentTasks() == null || task.getParentTasks().isEmpty())) {
      if (rootTskCandidate) {
        out.print(indentString(indent + 2));
        out.print("type:root stage;");
      } else {
        out.print(indentString(indent + 2));
        out.print("type:;");
      }

    } else {
      out.print(indentString(indent + 2));
      out.print("type:;");
      out.print("depends on:");
      boolean first = true;
      for (Task<? extends Serializable> parent : task.getParentTasks()) {
        if (!first) {
          out.print(",");
        }
        first = false;
        out.print(parent.getId());
      }
      out.print(";");

      if (task instanceof ConditionalTask
          && ((ConditionalTask) task).getListTasks() != null) {
        out.print("consists of:");
        first = true;
        for (Task<? extends Serializable> con : ((ConditionalTask) task)
            .getListTasks()) {
          if (!first) {
            out.print(",");
          }
          first = false;
          out.print(con.getId());
        }
        out.print(";");
      }
    }
    out.println();

    if (task instanceof ConditionalTask
        && ((ConditionalTask) task).getListTasks() != null) {
      for (Task<? extends Serializable> con : ((ConditionalTask) task)
          .getListTasks()) {
        outputDependencies(con, out, indent, false);
      }
    }

    if (task.getChildTasks() != null) {
      for (Task<? extends Serializable> child : task.getChildTasks()) {
        outputDependencies(child, out, indent, true);
      }
    }
  }

  public void outputAST(String treeString, PrintStream out, int indent) {
    out.print(indentString(indent));
    out.println("ABSTRACT SYNTAX TREE:");
    out.print(indentString(indent + 2));
    out.println(treeString);
  }

  public void outputDependencies(PrintStream out,
      List<Task<? extends Serializable>> rootTasks, int indent)
      throws Exception {
    out.print(indentString(indent));
    out.println("STAGE DEPENDENCIES:");
    for (Task<? extends Serializable> rootTask : rootTasks) {
      outputDependencies(rootTask, out, indent + 2, true);
    }
  }

  public void outputStagePlans(PrintStream out,
      List<Task<? extends Serializable>> rootTasks, int indent)
      throws Exception {
    out.print(indentString(indent));
    out.println("STAGE PLANS:");
    HashSet<Task<? extends Serializable>> displayedSet = new HashSet<Task<? extends Serializable>>();
    for (Task<? extends Serializable> rootTask : rootTasks) {
      outputPlan(rootTask, out, work.getExtended(), displayedSet, indent + 2);
    }
  }

  public static class MethodComparator implements Comparator {
    public int compare(Object o1, Object o2) {
      Method m1 = (Method) o1;
      Method m2 = (Method) o2;
      return m1.getName().compareTo(m2.getName());
    }
  }

  @Override
  public List<? extends Node> getChildren() {
    return super.getChildTasks();
  }

  @Override
  public String getName() {
    return "EXPLAIN";
  }
}
