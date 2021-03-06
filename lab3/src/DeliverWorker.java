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
		Message t_msg = null;
		BlockingQueue<Message> receive_queue = mp.getReceiveQueue();
		ConcurrentLinkedQueue<TimeStampedMessage> app_receive_queue = mp.getAppReceiveQueue();
		ConcurrentLinkedQueue<MulticastMessage> holdback_queue = mp.getHoldbackQueue();
		HashMap<Integer, HashMap<Integer, MulticastMessage> > sent_msgs = mp.getSentMsgs();
		BlockingQueue<Message> cs_reply_queue = mp.getCsReplyQueue();
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
			if(t_msg instanceof TimeStampedMessage)
			{
				app_receive_queue.add((TimeStampedMessage)t_msg);
				continue;
			}
			if(t_msg instanceof Message)
			{
				cs_reply_queue.add(t_msg);
				continue;
			}

			int request_seqnum = ((MulticastMessage)t_msg).getSeqNum();
			int to_group_id = ((MulticastMessage)t_msg).getGroupId();
			if(t_msg.getKind().equals("M_NACK"))
			{
//				System.out.println("received NACK from: " + t_msg.getSrc());
				/* send t_msg.src's all missing msgs */
				while(request_seqnum < mp.getSg()[to_group_id])
				{
					MulticastMessage m_msg = sent_msgs.get(to_group_id).get(request_seqnum).deepCopy();
					m_msg.setDest(t_msg.getSrc());
					mp.send(m_msg);
					request_seqnum++;
//					System.out.println("resend: " + m_msg);
				}
				continue;
			}
			wq.getMClock().syncWithMClock((MulticastMessage)t_msg);
//			System.out.println("received in deliverworker: " + t_msg);
			int from_local_id = ((MulticastMessage)t_msg).getFromLocalId();
			if(request_seqnum == (mp.getRqg()[from_local_id][to_group_id] + 1))
			{
				app_receive_queue.add((TimeStampedMessage)t_msg);
				mp.getRqg()[from_local_id][to_group_id]++;
				/*deliver all pending msgs from t_msg.src that could be delivered*/
				for(MulticastMessage mm_msg: holdback_queue)
				{
					if(mm_msg.getSrc().equals(t_msg.getSrc()))
					{
						if(mm_msg.getSeqNum() == mp.getRqg()[from_local_id][to_group_id] + 1)
						{
							holdback_queue.remove(mm_msg);
							app_receive_queue.add(mm_msg);
							mp.getRqg()[from_local_id][to_group_id]++;
						}
					}
				}
			}
			else if(request_seqnum > (mp.getRqg()[from_local_id][to_group_id] + 1))
			{
				holdback_queue.add((MulticastMessage)t_msg);
				/* send M_NACK to src for missing msgs */
				MulticastMessage tm_msg = new MulticastMessage(local_name, t_msg.getSrc(), "M_NACK", null, null, null, to_group_id);
				tm_msg.setSeqNum(mp.getRqg()[from_local_id][to_group_id] + 1);
				mp.send(tm_msg);
//				System.out.println("send NACK to: " + tm_msg.getDest());
			}
			else
				;
		}
	}
}
