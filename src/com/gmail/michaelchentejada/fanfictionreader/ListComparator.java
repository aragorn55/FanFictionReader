package com.gmail.michaelchentejada.fanfictionreader;

import java.util.Comparator;
import java.util.HashMap;

public class ListComparator implements Comparator<HashMap<String,String>> {

	boolean sortBy = true;
	public ListComparator(boolean SortBy) {
		sortBy = SortBy;
	}
	@Override
	public int compare(HashMap<String,String> arg0, HashMap<String,String> arg1) {
		if (sortBy){
			return arg0.get(Parser.TITLE).compareTo(arg1.get(Parser.TITLE));
		}else{
			if (Integer.parseInt(arg0.get(Parser.VIEWS_INT))>Integer.parseInt(arg1.get(Parser.VIEWS_INT))){
					return -1;
				}else if(Integer.parseInt(arg0.get(Parser.VIEWS_INT))<Integer.parseInt(arg1.get(Parser.VIEWS_INT))){
					return 1;
				}else{
					return 0;
				}	
		}	
	}
}
