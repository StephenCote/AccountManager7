package org.cote.accountmanager.analysis;

import java.util.ArrayList;
import java.util.List;

public class FaceRegion{
	private int x = 0;
	private int y = 0;
	private int w = 0;
	private int h = 0;
	private List<Integer> left_eye = new ArrayList<>();
	private List<Integer> right_eye = new ArrayList<>();
	public FaceRegion() {
		
	}
	public int getX() {
		return x;
	}
	public void setX(int x) {
		this.x = x;
	}
	public int getY() {
		return y;
	}
	public void setY(int y) {
		this.y = y;
	}
	public int getW() {
		return w;
	}
	public void setW(int w) {
		this.w = w;
	}
	public int getH() {
		return h;
	}
	public void setH(int h) {
		this.h = h;
	}
	public List<Integer> getLeft_eye() {
		return left_eye;
	}
	public void setLeft_eye(List<Integer> left_eye) {
		this.left_eye = left_eye;
	}
	public List<Integer> getRight_eye() {
		return right_eye;
	}
	public void setRight_eye(List<Integer> right_eye) {
		this.right_eye = right_eye;
	}
	
}
