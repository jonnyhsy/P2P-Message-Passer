package bin;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;

public class DeliverWorker extends Thread
{
	private MessagePasser mp;
	private String local_name;
	private WorkerQueue wq;
	private boolean flag = true;

	public DeliverWorker(MessagePasser mp, String local_name, WorkerQueue wq)
	{
		this.mp = mp;
		this.local_name = local_name;
		this.wq = wq;
	}
	public void setFlag(){flag = false;}
	public void run()
	{
		TimeStampedMessage t_msg = null;
		BlockingQueue<TimeStampedMessage> receive_queue = mp.getReceiveQueue();
		ConcurrentLinkedQueue<TimeStampedMessage> app_receive_queue = mp.getAppReceiveQueue();
		ConcurrentLinkedQueue<MulticastMessage> holdback_queue = mp.getHoldbackQueue();
		HashMap<Integer, MulticastMessage> sent_msgs = mp.getSentMsgs();

		while(flag)
		{
			try
			{
				t_msg = receive_queue.take();
			}
			catch(InterruptedException iex)
			{
				if(!flag)
					break;
				else
					iex.printStackTrace();
			}
			if(!(t_msg instanceof MulticastMessage))
			{
				app_receive_queue.add(t_msg);
				continue;
			}
			if(t_msg.getKind().equals("M_NACK"))
			{
				System.out.println("received NACK from: " + t_msg.getSrc());
				int request_seqnum = ((MulticastMessage)t_msg).getSeqNum();
				/* send t_msg.src's all missing msgs */
				while(request_seqnum < mp.getSeqNum())
				{
					if(sent_msgs.get(request_seqnum) == null)
						System.out.println("null request_seqnum: " + request_seqnum);
					MulticastMessage m_msg = sent_msgs.get(request_seqnum).deepCopy();
					m_msg.setDest(t_msg.getSrc());
					/* -------------------------------- */
					mp.send(m_msg);
					request_seqnum++;
					System.out.println("resend: " + m_msg);
				}
				continue;
			}
			wq.getMClock().syncWithMClock((MulticastMessage)t_msg);
			System.out.println("received in deliverworker: " + t_msg);
			if(((MulticastMessage)t_msg).getSeqNum() == (mp.getRqg().get(t_msg.getSrc()) + 1))
			{
				/*----------------- deliver it -----------------*/
				app_receive_queue.add(t_msg);
				mp.getRqg().put(t_msg.getSrc(), ((MulticastMessage)t_msg).getSeqNum()); // R_qg++
				/*deliver all pending msgs from t_msg.src that could be delivered*/
				for(MulticastMessage mm_msg: holdback_queue)
				{
					if(mm_msg.getSrc().equals(t_msg.getSrc()))
					{
						if(mm_msg.getSeqNum() == mp.getRqg().get(t_msg.getSrc()) + 1)
						{
							holdback_queue.remove(mm_msg);
							app_receive_queue.add(mm_msg);
							mp.getRqg().put(t_msg.getSrc(), mm_msg.getSeqNum());//R_qg++
						}
					}
				}
			}
			else if(((MulticastMessage)t_msg).getSeqNum() > (mp.getRqg().get(t_msg.getSrc()) + 1))
			{
				holdback_queue.add((MulticastMessage)t_msg);
				/* send M_NACK to src for missing msgs */
				MulticastMessage tm_msg = new MulticastMessage(local_name, t_msg.getSrc(), "M_NACK", null, null, null);
				tm_msg.setSeqNum(mp.getRqg().get(t_msg.getSrc())+1);
				mp.send(tm_msg);
				System.out.println("send NACK to: " + tm_msg.getDest());
			}
			else
				;
		}
	}
}
