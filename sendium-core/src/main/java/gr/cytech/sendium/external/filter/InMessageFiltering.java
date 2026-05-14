package gr.cytech.sendium.external.filter;

import gr.cytech.sendium.core.AbstractOutWorker;

import java.util.List;

public interface InMessageFiltering {
    String getBeforeInsertMessageFiltersList();

    List<AbstractOutWorker> getBeforeInsertMessageFilters();

    void setBeforeInsertMessageFilters(List<AbstractOutWorker> filters);
}
