package com.kopite.devspace.widget.application;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kopite.devspace.overview.application.ProjectCategoryCount;
import com.kopite.devspace.task.application.model.TaskSnapshot;
import com.kopite.devspace.journal.application.model.JournalSnapshot;
import com.kopite.devspace.milestone.application.model.MilestoneSnapshot;
import com.kopite.devspace.link.application.model.LinkSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(discriminatorProperty="kind",oneOf={WidgetPayload.Overview.class,WidgetPayload.Board.class,WidgetPayload.Journal.class,WidgetPayload.Milestone.class,WidgetPayload.Links.class})
public interface WidgetPayload {
    @JsonProperty("kind") String kind();
    @Schema(name="WidgetTaskStatistics") record Statistics(long todo,long doing,long done,long total,Instant asOf,boolean externalDataUnavailable){}
    @Schema(name="WidgetTaskColumn") record Column(String status,List<TaskSnapshot> items){public Column{items=List.copyOf(items);}}
    @Schema(name="OverviewWidgetData") record Overview(List<ProjectCategoryCount> projects,Statistics tasks) implements WidgetPayload {
        public Overview{projects=List.copyOf(projects);}@JsonProperty("kind") @Schema(allowableValues="overview") public String kind(){return "overview";}
    }
    @Schema(name="BoardWidgetData") record Board(List<Column> columns,Statistics statistics) implements WidgetPayload {
        public Board{columns=List.copyOf(columns);}@JsonProperty("kind") @Schema(allowableValues="board") public String kind(){return "board";}
    }
    @Schema(name="JournalWidgetData") record Journal(List<JournalSnapshot> items) implements WidgetPayload {
        public Journal{items=List.copyOf(items);}@JsonProperty("kind") @Schema(allowableValues="journal") public String kind(){return "journal";}
    }
    @Schema(name="MilestoneWidgetData") record Milestone(List<MilestoneSnapshot> items) implements WidgetPayload {
        public Milestone{items=List.copyOf(items);}@JsonProperty("kind") @Schema(allowableValues="milestone") public String kind(){return "milestone";}
    }
    @Schema(name="LinksWidgetData") record Links(List<LinkSnapshot> items,long collectionRevision) implements WidgetPayload {
        public Links{items=List.copyOf(items);}@JsonProperty("kind") @Schema(allowableValues="links") public String kind(){return "links";}
    }
}
