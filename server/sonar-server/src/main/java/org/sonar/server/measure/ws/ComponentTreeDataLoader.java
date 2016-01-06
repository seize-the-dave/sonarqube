/*
 * SonarQube :: Server
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.measure.ws;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.ResourceTypes;
import org.sonar.api.utils.Paging;
import org.sonar.api.web.UserRole;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentDtoWithSnapshotId;
import org.sonar.db.component.ComponentTreeQuery;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.measure.MeasureDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.metric.MetricDtoFunctions;
import org.sonar.server.component.ComponentFinder;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.user.UserSession;
import org.sonarqube.ws.WsMeasures;
import org.sonarqube.ws.client.measure.ComponentTreeWsRequest;

import static com.google.common.base.Objects.firstNonNull;
import static com.google.common.collect.FluentIterable.from;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.sonar.api.utils.DateUtils.formatDateTime;
import static org.sonar.server.component.ComponentFinder.ParamNames.BASE_COMPONENT_ID_AND_KEY;
import static org.sonar.server.measure.ws.ComponentTreeAction.ALL_STRATEGY;
import static org.sonar.server.measure.ws.ComponentTreeAction.CHILDREN_STRATEGY;
import static org.sonar.server.measure.ws.ComponentTreeAction.LEAVES_STRATEGY;
import static org.sonar.server.measure.ws.ComponentTreeAction.METRIC_SORT;
import static org.sonar.server.measure.ws.ComponentTreeAction.NAME_SORT;
import static org.sonar.server.user.AbstractUserSession.insufficientPrivilegesException;

public class ComponentTreeDataLoader {
  private static final Set<String> QUALIFIERS_ELIGIBLE_FOR_BEST_VALUE = newHashSet(Qualifiers.FILE, Qualifiers.UNIT_TEST_FILE);

  private final DbClient dbClient;
  private final ComponentFinder componentFinder;
  private final UserSession userSession;
  private final ResourceTypes resourceTypes;

  public ComponentTreeDataLoader(DbClient dbClient, ComponentFinder componentFinder, UserSession userSession, ResourceTypes resourceTypes) {
    this.dbClient = dbClient;
    this.componentFinder = componentFinder;
    this.userSession = userSession;
    this.resourceTypes = resourceTypes;
  }

  @CheckForNull
  ComponentTreeData load(ComponentTreeWsRequest wsRequest) {
    DbSession dbSession = dbClient.openSession(false);
    try {
      ComponentDto baseComponent = componentFinder.getByUuidOrKey(dbSession, wsRequest.getBaseComponentId(), wsRequest.getBaseComponentKey(), BASE_COMPONENT_ID_AND_KEY);
      checkPermissions(baseComponent);
      SnapshotDto baseSnapshot = dbClient.snapshotDao().selectLastSnapshotByComponentId(dbSession, baseComponent.getId());
      if (baseSnapshot == null) {
        return ComponentTreeData.builder()
          .setBaseComponent(baseComponent)
          .build();
      }

      ComponentTreeQuery dbQuery = toComponentTreeQuery(wsRequest, baseSnapshot);
      ComponentDtosAndTotal componentDtosAndTotal = searchComponents(dbSession, dbQuery, wsRequest);
      List<ComponentDtoWithSnapshotId> components = componentDtosAndTotal.componentDtos;
      int componentCount = componentDtosAndTotal.total;
      List<MetricDto> metrics = searchMetrics(dbSession, wsRequest);
      List<WsMeasures.Period> periods = periodsFromSnapshot(baseSnapshot);
      Table<String, MetricDto, MeasureDto> measuresByComponentUuidAndMetric = searchMeasuresByComponentUuidAndMetric(dbSession, components, metrics, periods);

      components = sortComponents(components, wsRequest, metrics, measuresByComponentUuidAndMetric);
      components = paginateComponents(components, componentCount, wsRequest);
      Map<Long, String> referenceComponentUuidsById = searchReferenceComponentUuidsById(dbSession, components);

      return ComponentTreeData.builder()
        .setBaseComponent(baseComponent)
        .setComponentsFromDb(components)
        .setComponentCount(componentCount)
        .setMeasuresByComponentUuidAndMetric(measuresByComponentUuidAndMetric)
        .setMetrics(metrics)
        .setPeriods(periods)
        .setReferenceComponentUuidsById(referenceComponentUuidsById)
        .build();
    } finally {
      dbClient.closeSession(dbSession);
    }
  }

  private Map<Long, String> searchReferenceComponentUuidsById(DbSession dbSession, List<ComponentDtoWithSnapshotId> components) {
    List<Long> referenceComponentIds = from(components)
      .transform(ComponentDtoWithSnapshotIdToCopyResourceIdFunction.INSTANCE)
      .filter(Predicates.<Long>notNull())
      .toList();
    if (referenceComponentIds.isEmpty()) {
      return emptyMap();
    }

    List<ComponentDto> referenceComponents = dbClient.componentDao().selectByIds(dbSession, referenceComponentIds);
    Map<Long, String> referenceComponentUuidsById = new HashMap<>();
    for (ComponentDto referenceComponent : referenceComponents) {
      referenceComponentUuidsById.put(referenceComponent.getId(), referenceComponent.uuid());
    }

    return referenceComponentUuidsById;
  }

  private ComponentDtosAndTotal searchComponents(DbSession dbSession, ComponentTreeQuery dbQuery, ComponentTreeWsRequest wsRequest) {
    switch (wsRequest.getStrategy()) {
      case CHILDREN_STRATEGY:
        return new ComponentDtosAndTotal(
          dbClient.componentDao().selectDirectChildren(dbSession, dbQuery),
          dbClient.componentDao().countDirectChildren(dbSession, dbQuery));
      case LEAVES_STRATEGY:
      case ALL_STRATEGY:
        return new ComponentDtosAndTotal(
          dbClient.componentDao().selectAllChildren(dbSession, dbQuery),
          dbClient.componentDao().countAllChildren(dbSession, dbQuery));
      default:
        throw new IllegalStateException("Unknown component tree strategy");
    }
  }

  private List<MetricDto> searchMetrics(DbSession dbSession, ComponentTreeWsRequest request) {
    List<MetricDto> metrics = dbClient.metricDao().selectByKeys(dbSession, request.getMetricKeys());
    if (metrics.size() < request.getMetricKeys().size()) {
      List<String> foundMetricKeys = Lists.transform(metrics, new Function<MetricDto, String>() {
        @Override
        public String apply(@Nonnull MetricDto input) {
          return input.getKey();
        }
      });
      Set<String> missingMetricKeys = Sets.difference(
        new LinkedHashSet<>(request.getMetricKeys()),
        new LinkedHashSet<>(foundMetricKeys));

      throw new NotFoundException(format("The following metric keys are not found: %s", Joiner.on(", ").join(missingMetricKeys)));
    }

    return metrics;
  }

  private Table<String, MetricDto, MeasureDto> searchMeasuresByComponentUuidAndMetric(DbSession dbSession, List<ComponentDtoWithSnapshotId> components, List<MetricDto> metrics,
    List<WsMeasures.Period> periods) {
    Map<Long, ComponentDtoWithSnapshotId> componentsBySnapshotId = Maps.uniqueIndex(components, ComponentDtoWithSnapshotIdToSnapshotIdFunction.INSTANCE);

    Map<Integer, MetricDto> metricsById = Maps.uniqueIndex(metrics, MetricDtoFunctions.toId());
    List<MeasureDto> measureDtos = dbClient.measureDao().selectBySnapshotIdsAndMetricIds(dbSession,
      new ArrayList<>(componentsBySnapshotId.keySet()),
      new ArrayList<>(metricsById.keySet()));

    Table<String, MetricDto, MeasureDto> measuresByComponentUuidAndMetric = HashBasedTable.create(components.size(), metrics.size());
    for (MeasureDto measureDto : measureDtos) {
      measuresByComponentUuidAndMetric.put(
        componentsBySnapshotId.get(measureDto.getSnapshotId()).uuid(),
        metricsById.get(measureDto.getMetricId()),
        measureDto);
    }

    addBestValuesToMeasures(measuresByComponentUuidAndMetric, components, metrics, periods);

    return measuresByComponentUuidAndMetric;
  }

  /**
   * Conditions for best value measure:
   * <ul>
   *   <li>component is a production file or test file</li>
   *   <li>metric is optimized for best value</li>
   * </ul>
   */
  private static void addBestValuesToMeasures(Table<String, MetricDto, MeasureDto> measuresByComponentUuidAndMetric, List<ComponentDtoWithSnapshotId> components,
    List<MetricDto> metrics, List<WsMeasures.Period> periods) {
    List<ComponentDtoWithSnapshotId> componentsEligibleForBestValue = from(components).filter(IsFileComponent.INSTANCE).toList();
    List<MetricDtoWithBestValue> metricDtosWithBestValueMeasure = from(metrics)
      .filter(IsMetricOptimizedForBestValue.INSTANCE)
      .transform(new MetricDtoToMetricDtoWithBestValue(periods))
      .toList();
    if (metricDtosWithBestValueMeasure.isEmpty()) {
      return;
    }

    for (ComponentDtoWithSnapshotId component : componentsEligibleForBestValue) {
      for (MetricDtoWithBestValue metricWithBestValue : metricDtosWithBestValueMeasure) {
        if (measuresByComponentUuidAndMetric.get(component.uuid(), metricWithBestValue.metric) == null) {
          measuresByComponentUuidAndMetric.put(component.uuid(), metricWithBestValue.metric, metricWithBestValue.bestValue);
        }
      }
    }
  }

  private static List<WsMeasures.Period> periodsFromSnapshot(SnapshotDto baseSnapshot) {
    List<WsMeasures.Period> periods = new ArrayList<>();
    for (int periodIndex = 1; periodIndex <= 5; periodIndex++) {
      if (baseSnapshot.getPeriodDate(periodIndex) != null) {
        periods.add(snapshotDtoToWsPeriod(baseSnapshot, periodIndex));
      }
    }

    return periods;
  }

  private static WsMeasures.Period snapshotDtoToWsPeriod(SnapshotDto snapshot, int periodIndex) {
    WsMeasures.Period.Builder period = WsMeasures.Period.newBuilder();
    period.setIndex(periodIndex);
    if (snapshot.getPeriodMode(periodIndex) != null) {
      period.setMode(snapshot.getPeriodMode(periodIndex));
    }
    if (snapshot.getPeriodModeParameter(periodIndex) != null) {
      period.setParameter(snapshot.getPeriodModeParameter(periodIndex));
    }
    if (snapshot.getPeriodDate(periodIndex) != null) {
      period.setDate(formatDateTime(snapshot.getPeriodDate(periodIndex)));
    }

    return period.build();
  }

  private static List<ComponentDtoWithSnapshotId> sortComponents(List<ComponentDtoWithSnapshotId> components, ComponentTreeWsRequest wsRequest, List<MetricDto> metrics,
    Table<String, MetricDto, MeasureDto> measuresByComponentUuidAndMetric) {
    if (!wsRequest.getSort().contains(METRIC_SORT)) {
      return components;
    }

    return ComponentTreeSort.sortComponents(components, wsRequest, metrics, measuresByComponentUuidAndMetric);
  }

  private static List<ComponentDtoWithSnapshotId> paginateComponents(List<ComponentDtoWithSnapshotId> components, int componentCount, ComponentTreeWsRequest wsRequest) {
    if (!wsRequest.getSort().contains(METRIC_SORT)) {
      return components;
    }

    Paging paging = Paging.forPageIndex(wsRequest.getPage())
      .withPageSize(wsRequest.getPageSize())
      .andTotal(componentCount);

    return from(components)
      .skip(paging.offset())
      .limit(paging.pageSize())
      .toList();
  }

  @CheckForNull
  private List<String> childrenQualifiers(ComponentTreeWsRequest request, String baseQualifier) {
    List<String> requestQualifiers = request.getQualifiers();
    List<String> childrenQualifiers = null;
    if (LEAVES_STRATEGY.equals(request.getStrategy())) {
      childrenQualifiers = resourceTypes.getLeavesQualifiers(baseQualifier);
    }

    if (requestQualifiers == null) {
      return childrenQualifiers;
    }

    if (childrenQualifiers == null) {
      return requestQualifiers;
    }

    // intersection of request and children qualifiers
    childrenQualifiers.retainAll(requestQualifiers);

    return childrenQualifiers;
  }

  private ComponentTreeQuery toComponentTreeQuery(ComponentTreeWsRequest wsRequest, SnapshotDto baseSnapshot) {
    List<String> childrenQualifiers = childrenQualifiers(wsRequest, baseSnapshot.getQualifier());

    List<String> sortsWithoutMetricSort = newArrayList(Iterables.filter(wsRequest.getSort(), IsNotMetricSort.INSTANCE));
    sortsWithoutMetricSort = sortsWithoutMetricSort.isEmpty() ? singletonList(NAME_SORT) : sortsWithoutMetricSort;

    ComponentTreeQuery.Builder dbQuery = ComponentTreeQuery.builder()
      .setBaseSnapshot(baseSnapshot)
      .setPage(wsRequest.getPage())
      .setPageSize(wsRequest.getPageSize())
      .setSortFields(sortsWithoutMetricSort)
      .setAsc(wsRequest.getAsc());

    if (wsRequest.getQuery() != null) {
      dbQuery.setNameOrKeyQuery(wsRequest.getQuery());
    }
    if (childrenQualifiers != null) {
      dbQuery.setQualifiers(childrenQualifiers);
    }
    // load all components if we must sort by metric value
    if (wsRequest.getSort().contains(METRIC_SORT)) {
      dbQuery.setPage(1);
      dbQuery.setPageSize(Integer.MAX_VALUE);
    }

    return dbQuery.build();
  }

  private void checkPermissions(ComponentDto baseComponent) {
    String projectUuid = firstNonNull(baseComponent.projectUuid(), baseComponent.uuid());
    if (!userSession.hasGlobalPermission(GlobalPermissions.SYSTEM_ADMIN) &&
      !userSession.hasProjectPermissionByUuid(UserRole.ADMIN, projectUuid) &&
      !userSession.hasProjectPermissionByUuid(UserRole.USER, projectUuid)) {
      throw insufficientPrivilegesException();
    }
  }

  private static class ComponentDtosAndTotal {
    private final List<ComponentDtoWithSnapshotId> componentDtos;
    private final int total;

    private ComponentDtosAndTotal(List<ComponentDtoWithSnapshotId> componentDtos, int total) {
      this.componentDtos = componentDtos;
      this.total = total;
    }
  }

  private enum IsMetricOptimizedForBestValue implements Predicate<MetricDto> {
    INSTANCE;

    @Override
    public boolean apply(@Nonnull MetricDto input) {
      return input.isOptimizedBestValue() && input.getBestValue() != null;
    }
  }

  private enum IsFileComponent implements Predicate<ComponentDtoWithSnapshotId> {
    INSTANCE;

    @Override
    public boolean apply(@Nonnull ComponentDtoWithSnapshotId input) {
      return QUALIFIERS_ELIGIBLE_FOR_BEST_VALUE.contains(input.qualifier());
    }
  }

  private static class MetricDtoToMetricDtoWithBestValue implements Function<MetricDto, MetricDtoWithBestValue> {
    private final List<Integer> periodIndexes;

    MetricDtoToMetricDtoWithBestValue(List<WsMeasures.Period> periods) {
      this.periodIndexes = Lists.transform(periods, WsPeriodToIndex.INSTANCE);
    }

    @Override
    public MetricDtoWithBestValue apply(@Nonnull MetricDto input) {
      return new MetricDtoWithBestValue(input, periodIndexes);
    }
  }

  private static class MetricDtoWithBestValue {
    private final MetricDto metric;

    private final MeasureDto bestValue;

    private MetricDtoWithBestValue(MetricDto metric, List<Integer> periodIndexes) {
      this.metric = metric;
      MeasureDto measure = new MeasureDto()
        .setMetricId(metric.getId())
        .setMetricKey(metric.getKey())
        .setValue(metric.getBestValue());
      for (Integer periodIndex : periodIndexes) {
        measure.setVariation(periodIndex, 0.0d);
      }
      this.bestValue = measure;
    }
  }

  private enum WsPeriodToIndex implements Function<WsMeasures.Period, Integer> {
    INSTANCE;

    @Override
    public Integer apply(@Nonnull WsMeasures.Period input) {
      return input.getIndex();
    }
  }

  private enum ComponentDtoWithSnapshotIdToSnapshotIdFunction implements Function<ComponentDtoWithSnapshotId, Long> {
    INSTANCE;

    @Override
    public Long apply(@Nonnull ComponentDtoWithSnapshotId input) {
      return input.getSnapshotId();
    }
  }

  private enum IsNotMetricSort implements Predicate<String> {
    INSTANCE;

    @Override
    public boolean apply(@Nonnull String input) {
      return !input.equals(METRIC_SORT);
    }
  }

  private enum ComponentDtoWithSnapshotIdToCopyResourceIdFunction implements Function<ComponentDtoWithSnapshotId, Long> {
    INSTANCE;
    @Override
    public Long apply(@Nonnull ComponentDtoWithSnapshotId input) {
      return input.getCopyResourceId();
    }
  }
}
