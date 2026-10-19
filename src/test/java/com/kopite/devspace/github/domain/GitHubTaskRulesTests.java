package com.kopite.devspace.github.domain;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GitHubTaskRulesTests {
    final GitHubTaskMapping mapping=new GitHubTaskMapping(1L,2L,3L);
    @Test void closureOverridesLabelsButUnplannedAndUnknownPreservePrevious() {
        assertThat(mapping.decide("closed","completed",List.of(1L,2L),Set.of(1L,2L,3L),"doing",2L).status()).isEqualTo("done");
        assertThat(mapping.decide("closed","not_planned",List.of(),Set.of(),"doing",2L).reason()).isEqualTo("not_planned");
        assertThat(mapping.decide("closed","future_reason",List.of(),Set.of(),null,null).status()).isEqualTo("todo");
    }
    @Test void conflictAndDeletedMappingPreserveOnlyAffectedState() {
        assertThat(mapping.decide("open",null,List.of(1L,2L),Set.of(1L,2L),"done",3L).status()).isEqualTo("done");
        assertThat(mapping.decide("open",null,List.of(),Set.of(1L,3L),"doing",2L).reason()).isEqualTo("mapping_error");
        assertThat(mapping.decide("open",null,List.of(1L),Set.of(1L,3L),"doing",2L).status()).isEqualTo("todo");
        assertThat(mapping.decide("open",null,List.of(),Set.of(1L,2L,3L),"doing",2L).status()).isEqualTo("todo");
        assertThatThrownBy(()->new GitHubTaskMapping(1L,1L,null)).isInstanceOf(GitHubException.class);
    }
    @Test void criteriaUsesAllLabelsAnyAssigneeAndAcrossGroups() {
        var criteria=new GitHubImportCriteria(List.of(1L,2L),List.of(10L,20L));
        assertThat(criteria.matches(List.of(1L,2L),List.of(20L))).isTrue();
        assertThat(criteria.matches(List.of(1L),List.of(20L))).isFalse();
        assertThat(criteria.matches(List.of(1L,2L),List.of(30L))).isFalse();
        assertThat(new GitHubImportCriteria(List.of(),List.of()).matches(List.of(),List.of())).isTrue();
    }
}
